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

    @Test fun partThatMayBeTheOnlyCopyIsRestoredNotDeleted() = runBlocking<Unit> {
        val dest = h.dest.apply { mkdirs() }
        val sid = h.ledger.insertSession(ImportSessionEntity(volumeId = h.volume.id, volumeLabel = "x", startedAt = 1, moveMode = true))

        // 1) 카드 원본까지 지웠는데(SOURCE_DELETED) 전원 차단으로 이름 변경이 사라져 .part만 남음 → 되살림, 상태 유지
        val a = randomBytes(11, 6_000)
        val aFinal = File(dest, "IMG_0011.JPG")
        val aPart = File(dest, "IMG_0011.JPG.photocull.part").apply { writeBytes(a) }
        val aId = h.ledger.insertItem(row(sid, "DCIM/100CANON/IMG_0011.JPG", aFinal, ImportStatus.SOURCE_DELETED, sha256(a), a.size.toLong()))

        // 2) 검증 기록(COPIED)까지 남기고 이름 변경 전후에 종료 → 되살리고 VERIFIED
        val b = randomBytes(12, 7_000)
        val bFinal = File(dest, "2026-10/IMG_0012.CR3").apply { parentFile!!.mkdirs() }
        val bPart = PartFiles.partOf(bFinal).apply { writeBytes(b) }
        val bId = h.ledger.insertItem(row(sid, "DCIM/100CANON/IMG_0012.CR3", bFinal, ImportStatus.COPIED, sha256(b), b.size.toLong()))

        // 3) 최종 파일이 이미 있으면 .part는 남은 찌꺼기 → 삭제(최종 파일은 그대로)
        val c = randomBytes(13, 3_000)
        val cFinal = File(dest, "IMG_0013.JPG").apply { writeBytes(c) }
        val cPart = PartFiles.partOf(cFinal).apply { writeBytes(c) }
        val cId = h.ledger.insertItem(row(sid, "DCIM/100CANON/IMG_0013.JPG", cFinal, ImportStatus.VERIFIED, sha256(c), c.size.toLong()))

        // 4) 기록과 크기는 같지만 내용이 다른 .part(복사 도중 멈춤) → 삭제, 카드 원본이 아직 있으므로 실패 행으로
        val d = randomBytes(14, 4_000)
        val dFinal = File(dest, "IMG_0014.JPG")
        val dPart = PartFiles.partOf(dFinal).apply { writeBytes(randomBytes(15, 4_000)) }
        val dId = h.ledger.insertItem(row(sid, "DCIM/100CANON/IMG_0014.JPG", dFinal, ImportStatus.COPIED, sha256(d), d.size.toLong()))

        // 5) 저장 폴더 설정이 바뀌어 저장 폴더 밖에 있는 멈춘 행의 .part도 같은 규칙
        val e = randomBytes(16, 2_000)
        val eFinal = File(tmp.root, "old/IMG_0016.JPG").apply { parentFile!!.mkdirs() }
        val ePart = PartFiles.partOf(eFinal).apply { writeBytes(e) }
        val eId = h.ledger.insertItem(row(sid, "DCIM/100CANON/IMG_0016.JPG", eFinal, ImportStatus.COPIED, sha256(e), e.size.toLong()))

        val report = ImportRecovery(h.ledger).run(dest)

        assertThat(report.restoredPartFiles).isEqualTo(3)
        assertThat(report.removedPartFiles).isEqualTo(2)
        assertThat(report.abandonedItems).isEqualTo(1)
        for ((final, bytes) in listOf(aFinal to a, bFinal to b, cFinal to c, eFinal to e)) {
            assertThat(sha256(final)).isEqualTo(sha256(bytes))
        }
        for (part in listOf(aPart, bPart, cPart, dPart, ePart)) assertThat(part.exists()).isFalse()
        assertThat(dFinal.exists()).isFalse()

        val dao = h.db.mediaDao()
        assertThat(dao.get(aId)!!.importStatus).isEqualTo(ImportStatus.SOURCE_DELETED)
        assertThat(dao.get(bId)!!.importStatus).isEqualTo(ImportStatus.VERIFIED)
        assertThat(dao.get(cId)!!.importStatus).isEqualTo(ImportStatus.VERIFIED)
        assertThat(dao.get(dId)!!.importStatus).isEqualTo(ImportStatus.FAILED)
        assertThat(dao.get(eId)!!.importStatus).isEqualTo(ImportStatus.VERIFIED)
        assertThat(aFinal.lastModified()).isEqualTo(DEFAULT_MTIME)
    }

    @Test fun unreadablePartIsKept() = runBlocking<Unit> {
        val dest = h.dest.apply { mkdirs() }
        val sid = h.ledger.insertSession(ImportSessionEntity(volumeId = h.volume.id, volumeLabel = "x", startedAt = 1, moveMode = true))
        val a = randomBytes(21, 1_000)
        val final = File(dest, "IMG_0021.JPG")
        val part = PartFiles.partOf(final).apply { writeBytes(a) }
        h.ledger.insertItem(row(sid, "DCIM/100CANON/IMG_0021.JPG", final, ImportStatus.SOURCE_DELETED, sha256(a), a.size.toLong()))
        // 읽을 수 없으면 유일한 복사본일 수 있으므로 지우지 않는다
        val unreadable = object : DestinationFiles by DestinationFiles.Default {
            override fun openForRead(file: File): java.io.InputStream = throw java.io.IOException("EIO")
        }
        val report = ImportRecovery(h.ledger, unreadable).run(dest)
        assertThat(report.removedPartFiles).isEqualTo(0)
        assertThat(part.exists()).isTrue()
    }
}
