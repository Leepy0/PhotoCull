package com.leepy0.photocull.importer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.data.db.ImportSessionEntity
import com.leepy0.photocull.data.db.ImportStatus
import com.leepy0.photocull.data.db.MediaItemEntity
import com.leepy0.photocull.data.db.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ImportRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var h: EngineHarness

    @Before fun setUp() {
        h = EngineHarness(tmp.root)
    }

    @After fun tearDown() {
        h.close()
    }

    private fun row(sessionId: Long, rel: String, dst: File, status: ImportStatus, sha: String?, size: Long) = MediaItemEntity(
        sessionId = sessionId,
        volumeId = h.volume.id,
        srcRelPath = rel,
        srcLocation = "/card/$rel",
        srcSize = size,
        srcLastModified = DEFAULT_MTIME,
        unitKey = "$sessionId:${rel.substringBeforeLast('.')}",
        isPrimary = true,
        type = MediaType.PHOTO,
        mimeType = "image/jpeg",
        dstPath = dst.path,
        fileName = dst.name,
        sha256 = sha,
        importStatus = status,
        capturedAt = DEFAULT_MTIME,
    )

    @Test fun cleansOnlyPartFilesAndRepairsInterruptedRows() = runBlocking<Unit> {
        val dest = h.dest.apply { mkdirs() }
        val sid = h.ledger.insertSession(ImportSessionEntity(volumeId = h.volume.id, volumeLabel = "x", startedAt = 1, moveMode = true))

        // 1) 이름 변경까지 끝났는데 DB가 VERIFIED로 바뀌기 전에 종료됨 → 해시가 맞으면 VERIFIED로 복구
        val good = randomBytes(1, 5_000)
        val goodFile = File(dest, "IMG_0001.JPG").apply { writeBytes(good) }
        val goodId = h.ledger.insertItem(row(sid, "DCIM/100CANON/IMG_0001.JPG", goodFile, ImportStatus.COPIED, sha256(good), good.size.toLong()))

        // 2) .part 복사 중 종료 → .part 삭제, 실패 행으로 정리(폰 경로 없음)
        val partTarget = File(dest, "IMG_0002.JPG")
        val part = File(dest, "IMG_0002.JPG.photocull.part").apply { writeBytes(randomBytes(2, 100)) }
        val pendingId = h.ledger.insertItem(row(sid, "DCIM/100CANON/IMG_0002.JPG", partTarget, ImportStatus.PENDING, null, 5_000))

        // 3) COPIED인데 최종 파일 내용이 다름(다른 파일이 그 이름을 차지) → 그 파일은 건드리지 않고 행만 정리
        val foreign = File(dest, "IMG_0003.JPG").apply { writeBytes(randomBytes(3, 5_000)) }
        val mismatchId = h.ledger.insertItem(row(sid, "DCIM/100CANON/IMG_0003.JPG", foreign, ImportStatus.COPIED, sha256(randomBytes(4, 5_000)), 5_000))

        // 정리 대상이 아닌 파일들
        val otherPart = File(dest, "movie.part").apply { writeBytes(randomBytes(5, 10)) }
        val nestedPart = File(dest, "2026-01/IMG_0009.CR3.photocull.part").apply { parentFile!!.mkdirs(); writeBytes(randomBytes(6, 10)) }

        val report = ImportRecovery(h.ledger).run(dest)

        assertThat(report.recoveredItems).isEqualTo(1)
        assertThat(report.abandonedItems).isEqualTo(2)
        assertThat(report.removedPartFiles).isEqualTo(2)
        assertThat(part.exists()).isFalse()
        assertThat(nestedPart.exists()).isFalse()
        assertThat(otherPart.exists()).isTrue()
        assertThat(goodFile.exists()).isTrue()
        assertThat(foreign.exists()).isTrue()

        val recovered = h.db.mediaDao().get(goodId)!!
        assertThat(recovered.importStatus).isEqualTo(ImportStatus.VERIFIED)
        assertThat(recovered.dstPath).isEqualTo(goodFile.path)
        for (id in listOf(pendingId, mismatchId)) {
            val dead = h.db.mediaDao().get(id)!!
            assertThat(dead.importStatus).isEqualTo(ImportStatus.FAILED)
            assertThat(dead.dstPath).isNull()
            assertThat(dead.isPrimary).isFalse()
            assertThat(UnitKeys.isDetached(dead.unitKey)).isTrue()
        }
        // 복구된 행은 다음 스캔에서 이미 가져온 파일로 대조된다
        assertThat(h.ledger.importedKeys(h.volume.id).map { it.srcRelPath }).containsExactly("DCIM/100CANON/IMG_0001.JPG")
    }
}
