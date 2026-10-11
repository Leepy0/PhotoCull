package com.leepy0.photocull.importer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.data.db.ImportStatus
import com.leepy0.photocull.data.db.MediaItemEntity
import com.leepy0.photocull.data.db.ReviewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** 리뷰에서 확인된 데이터 안전·검토 누락 문제의 회귀 테스트 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ImportEngineSafetyTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var h: EngineHarness

    private val jpg1 = randomBytes(1, 300_000)
    private val cr31 = randomBytes(2, 700_000)
    private val jpg2 = randomBytes(3, 250_000)
    private val cr32 = randomBytes(4, 650_000)
    private val mp4 = randomBytes(5, 900_000)
    private val hif = randomBytes(6, 400_000)

    private val jpgPath = "DCIM/100CANON/IMG_0001.JPG"
    private val cr3Path = "DCIM/100CANON/IMG_0001.CR3"

    @Before fun setUp() {
        h = EngineHarness(tmp.root)
    }

    @After fun tearDown() {
        h.close()
    }

    private fun standardCard() {
        h.card.put("DCIM/100CANON/IMG_0001.JPG", jpg1, DEFAULT_MTIME)
        h.card.put("DCIM/100CANON/IMG_0001.CR3", cr31, DEFAULT_MTIME)
        h.card.put("DCIM/100CANON/IMG_0002.JPG", jpg2, DEFAULT_MTIME + 2_000)
        h.card.put("DCIM/100CANON/IMG_0002.CR3", cr32, DEFAULT_MTIME + 2_000)
        h.card.put("DCIM/100CANON/MVI_0003.MP4", mp4, DEFAULT_MTIME + 60_000)
    }

    private fun stored(rel: String): MediaItemEntity =
        h.items().last { it.srcRelPath == rel && it.importStatus != ImportStatus.FAILED }

    // ------------------------------------------------------------ 1. 다른 촬영에 붙지 않기

    @Test fun fileNumberResetDoesNotAttachNewShotToOldUnrelatedUnit() {
        // 지난 촬영: JPG만 찍었고 이동 모드로 카드에서 지워짐
        h.card.put(jpgPath, jpg1, DEFAULT_MTIME)
        h.run(move = true)
        val oldJpg = h.item(jpgPath)
        assertThat(h.card.file(jpgPath).exists()).isFalse()

        // 카드가 비어 파일 번호가 다시 1부터: 하루 뒤의 다른 촬영 IMG_0001.HIF + IMG_0001.CR3
        val dayLater = DEFAULT_MTIME + 24L * 3600 * 1000
        h.card.put("DCIM/100CANON/IMG_0001.HIF", hif, dayLater)
        h.card.put(cr3Path, cr31, dayLater)
        val outcome = h.run(move = true)

        assertThat(outcome.error).isNull()
        val newHif = h.item("DCIM/100CANON/IMG_0001.HIF")
        val newCr3 = h.item(cr3Path)
        // 독립된 새 단위: 대표가 있어 검토에 나오고, 예전 JPG 단위와 섞이지 않는다
        assertThat(newHif.unitKey).isNotEqualTo(oldJpg.unitKey)
        assertThat(newCr3.unitKey).isEqualTo(newHif.unitKey)
        assertThat(newHif.isPrimary).isTrue()
        assertThat(newCr3.isPrimary).isFalse()
        // 예전 JPG 옆에 짝처럼 놓이지 않는다
        assertThat(h.phoneFiles().keys).containsExactly("IMG_0001.JPG", "IMG_0001_1.HIF", "IMG_0001_1.CR3")
        h.assertSafetyInvariant()
    }

    @Test fun unknownModifiedTimeNeverAttachesToStoredUnit() {
        h.card.put(jpgPath, jpg1, DEFAULT_MTIME)
        h.run(move = true)
        val oldJpg = h.item(jpgPath)
        // 수정 시각을 모르면(SAF가 0을 줄 때) 같은 촬영인지 알 수 없다
        h.card.put(cr3Path, cr31, 0)
        h.run(move = true)
        val cr3 = h.item(cr3Path)
        assertThat(cr3.unitKey).isNotEqualTo(oldJpg.unitKey)
        assertThat(cr3.isPrimary).isTrue()
        h.assertSafetyInvariant()
    }

    // ------------------------------------------------------------ 7. 휴지통·삭제된 단위에 붙지 않기

    @Test fun resumedRawDoesNotJoinUnitTheUserAlreadyTrashed() {
        h.card.put(jpgPath, jpg1, DEFAULT_MTIME)
        h.card.put(cr3Path, cr31, DEFAULT_MTIME)
        // JPG를 옮긴 뒤 CR3 복사 중 카드 분리
        h.card.removeWhileReading = "IMG_0001.CR3"
        h.card.removeAfterBytes = 100_000
        assertThat(h.run(move = true).error).isInstanceOf(ImportError.CardRemoved::class.java)
        val jpg = h.item(jpgPath)
        assertThat(jpg.importStatus).isEqualTo(ImportStatus.VERIFIED)

        for (state in listOf(ReviewState.TRASHED, ReviewState.DELETED)) {
            // 검토에서 JPG를 휴지통으로(또는 영구 삭제)
            h.db.openHelper.writableDatabase.execSQL("UPDATE media_item SET reviewState = '${state.name}' WHERE unitKey = '${jpg.unitKey}'")
            h.card.removeWhileReading = null
            h.card.present = true
            h.run(move = false)

            val cr3 = stored(cr3Path)
            assertThat(cr3.unitKey).isNotEqualTo(jpg.unitKey)
            assertThat(cr3.isPrimary).isTrue()
            assertThat(cr3.reviewState).isEqualTo(ReviewState.PENDING)
            h.assertSafetyInvariant()
            // 다음 반복을 위해 이번에 가져온 CR3 기록과 파일을 지운다
            h.db.openHelper.writableDatabase.execSQL("DELETE FROM media_item WHERE id = ${cr3.id}")
            File(cr3.dstPath!!).delete()
        }
    }

    @Test fun resumeIntoLiveUnitStillAttaches() {
        h.card.put(jpgPath, jpg1, DEFAULT_MTIME)
        h.card.put(cr3Path, cr31, DEFAULT_MTIME + 1_000) // 같은 촬영(1초 차이)
        h.card.removeWhileReading = "IMG_0001.CR3"
        h.card.removeAfterBytes = 100_000
        h.run(move = true)
        val jpg = h.item(jpgPath)
        // 사용자가 남기기로 함(KEPT도 살아 있는 단위)
        h.db.openHelper.writableDatabase.execSQL("UPDATE media_item SET reviewState = 'KEPT' WHERE id = ${jpg.id}")
        h.card.removeWhileReading = null
        h.card.present = true
        h.run(move = true)
        val cr3 = stored(cr3Path)
        assertThat(cr3.unitKey).isEqualTo(jpg.unitKey)
        assertThat(cr3.isPrimary).isFalse()
        assertThat(h.phoneFiles().keys).containsExactly("IMG_0001.JPG", "IMG_0001.CR3")
        h.assertSafetyInvariant()
    }

    // ------------------------------------------------------------ 2. 이름 변경 내구성

    @Test fun renameIsFsyncedBeforeCardOriginalIsDeleted() {
        standardCard()
        val io = RecordingFiles()
        val cardPresentAtSync = HashMap<String, Boolean>()
        io.onSync = { f -> cardPresentAtSync[f.name] = h.card.file("DCIM/100CANON/${f.name}").exists() }
        h.run(move = true, io = io)

        assertThat(h.phoneFiles()).hasSize(5)
        for (name in h.phoneFiles().keys) {
            val moved = io.events.indexOf("move:$name")
            val synced = io.events.indexOf("sync:$name")
            assertThat(moved).isAtLeast(0)
            assertThat(synced).isGreaterThan(moved)
            assertWithName(name, cardPresentAtSync[name])
        }
        h.assertSafetyInvariant()
    }

    private fun assertWithName(name: String, presentAtSync: Boolean?) {
        com.google.common.truth.Truth.assertWithMessage("card original of $name must still exist when its copy is fsynced")
            .that(presentAtSync).isTrue()
    }

    @Test fun fsyncFailureKeepsCardOriginal() {
        h.card.put(jpgPath, jpg1)
        h.card.put(cr3Path, cr31)
        val io = RecordingFiles().apply { failSync += "IMG_0001.CR3" }
        val outcome = h.run(move = true, io = io)

        assertThat(outcome.error).isNull()
        assertThat(outcome.result.savedCount).isEqualTo(2)
        assertThat(outcome.result.sourceDeleteFailedCount).isEqualTo(1)
        assertThat(h.card.file(cr3Path).exists()).isTrue()
        assertThat(h.card.file(jpgPath).exists()).isFalse()
        assertThat(h.item(cr3Path).importStatus).isEqualTo(ImportStatus.SOURCE_DELETE_FAILED)
        assertThat(sha256(File(h.dest, "IMG_0001.CR3"))).isEqualTo(sha256(cr31))
        h.assertSafetyInvariant()
    }

    @Test fun lostRenameAfterPowerLossIsRestoredNotDeleted() {
        standardCard()
        h.run(move = true)
        // 전원 차단: .part 데이터는 디스크에 있었지만 이름 변경이 남지 못했고, 카드 원본은 이미 지워졌다
        val final = File(h.dest, "IMG_0002.CR3")
        val part = PartFiles.partOf(final)
        assertThat(final.renameTo(part)).isTrue()
        assertThat(h.card.file("DCIM/100CANON/IMG_0002.CR3").exists()).isFalse()

        // 다음 가져오기는 시작할 때 남은 .part를 처리한다: 유일한 복사본이므로 지우지 않고 되살린다
        h.card.put("DCIM/100CANON/IMG_0004.JPG", randomBytes(40, 1_000), DEFAULT_MTIME + 120_000)
        val outcome = h.run(move = true)
        assertThat(outcome.error).isNull()
        assertThat(part.exists()).isFalse()
        assertThat(sha256(final)).isEqualTo(sha256(cr32))
        assertThat(h.item("DCIM/100CANON/IMG_0002.CR3").importStatus).isEqualTo(ImportStatus.SOURCE_DELETED)
        h.assertSafetyInvariant()
    }

    // ------------------------------------------------------------ 6. 이름 변경 뒤 중단

    @Test fun failureAfterRenameKeepsRowSoRecoveryAdoptsTheFile() {
        h.card.put(jpgPath, jpg1)
        // 이름 변경 뒤 VERIFIED 기록에서 실패(디스크 오류·취소 등)
        val failing = object : ImportLedger by h.ledger {
            override suspend fun updateImportFields(item: MediaItemEntity) {
                if (item.importStatus == ImportStatus.VERIFIED) throw IllegalStateException("disk I/O error")
                h.ledger.updateImportFields(item)
            }
        }
        val outcome = h.run(move = true, ledger = failing)
        assertThat(outcome.error).isInstanceOf(ImportError.Unexpected::class.java)

        val row = h.item(jpgPath)
        val final = File(h.dest, "IMG_0001.JPG")
        // 행을 떼어내지 않는다: 최종 파일의 주인으로 남아 복구가 VERIFIED로 올릴 수 있다
        assertThat(row.importStatus).isEqualTo(ImportStatus.COPIED)
        assertThat(row.dstPath).isEqualTo(final.path)
        assertThat(row.sha256).isEqualTo(sha256(jpg1))
        assertThat(row.isPrimary).isTrue()
        assertThat(UnitKeys.isDetached(row.unitKey)).isFalse()
        assertThat(sha256(final)).isEqualTo(sha256(jpg1))
        assertThat(h.card.file(jpgPath).exists()).isTrue()

        val report = runBlocking { ImportRecovery(h.ledger).run(h.dest) }
        assertThat(report.recoveredItems).isEqualTo(1)
        assertThat(h.item(jpgPath).importStatus).isEqualTo(ImportStatus.VERIFIED)

        // 다음 이동: 복사 없이(주인 없는 _1 사본 없이) 카드 원본만 지운다
        val again = h.run(move = true)
        assertThat(again.result.savedCount).isEqualTo(0)
        assertThat(h.phoneFiles().keys).containsExactly("IMG_0001.JPG")
        assertThat(h.card.file(jpgPath).exists()).isFalse()
        assertThat(h.item(jpgPath).importStatus).isEqualTo(ImportStatus.SOURCE_DELETED)
        h.assertSafetyInvariant()
    }

    // ------------------------------------------------------------ 4. 쓰기 금지 카드

    @Test fun writeProtectedCardFailsFastAndStopsTryingDeletes() {
        standardCard()
        for (rel in h.card.originals.keys) h.card.failDeletes += rel.substringAfterLast('/')
        val grace = 10_000L
        val started = System.nanoTime()
        val outcome = h.run(move = true, removalGraceMs = grace)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertThat(outcome.error).isNull()
        assertThat(outcome.result.savedCount).isEqualTo(5)
        assertThat(outcome.result.sourceDeleteFailedCount).isEqualTo(5)
        // 카드가 그대로 있는데 못 지우면 분리 여부를 기다리지 않는다(파일마다 2초씩 늘어나지 않게)
        assertThat(elapsedMs).isLessThan(grace)
        // 처음 몇 개가 모두 실패하면 나머지는 시도하지 않는다
        assertThat(h.card.deleteCalls).isEqualTo(ImportEngine.READ_ONLY_AFTER_FAILURES)
        for (rel in h.card.originals.keys) {
            assertThat(h.card.file(rel).exists()).isTrue()
            assertThat(h.item(rel).importStatus).isEqualTo(ImportStatus.SOURCE_DELETE_FAILED)
        }
        h.assertSafetyInvariant()
    }

    @Test fun cardRemovedAtDeleteIsStillReportedAsRemoval() {
        h.card.put(jpgPath, jpg1)
        h.card.put(cr3Path, cr31)
        h.card.beforeDelete = { h.card.present = false }
        val outcome = h.run(move = true)
        assertThat(outcome.error).isInstanceOf(ImportError.CardRemoved::class.java)
        assertThat(h.item(jpgPath).importStatus).isEqualTo(ImportStatus.VERIFIED)
        h.assertSafetyInvariant()
    }

    // ------------------------------------------------------------ 5. 카드 파일 해시 확인

    @Test fun ledgerMatchWithDifferentCardContentKeepsCardFile() {
        h.card.put(jpgPath, jpg1, DEFAULT_MTIME)
        h.run(move = false)
        // 같은 경로·크기·수정 시각이지만 내용이 다른 카드 파일
        val other = randomBytes(99, jpg1.size)
        h.card.put(jpgPath, other, DEFAULT_MTIME)
        assertThat(h.plan().summary.alreadyImportedCount).isEqualTo(1)

        h.run(move = true)
        assertThat(h.card.file(jpgPath).exists()).isTrue()
        assertThat(sha256(h.card.file(jpgPath))).isEqualTo(sha256(other))
        assertThat(sha256(File(h.dest, "IMG_0001.JPG"))).isEqualTo(sha256(jpg1))
        h.assertSafetyInvariant()
    }

    // ------------------------------------------------------------ 9. 중복 파일의 주인

    @Test fun reimportAfterAppDataLossAdoptsExistingFilesForReview() {
        standardCard()
        h.run(move = false)
        // 앱 데이터 삭제(재설치·저장소 비우기): 기록만 사라지고 폰 파일은 그대로
        h.db.openHelper.writableDatabase.execSQL("DELETE FROM media_item")
        h.kicks = 0

        val outcome = h.run(move = true)
        assertThat(outcome.error).isNull()
        assertThat(outcome.result.savedCount).isEqualTo(0)
        assertThat(outcome.result.duplicateCount).isEqualTo(5)
        assertThat(h.phoneFiles()).hasSize(5) // _1 사본 없음

        val jpg = h.item(jpgPath)
        val cr3 = h.item(cr3Path)
        val mvi = h.item("DCIM/100CANON/MVI_0003.MP4")
        for (row in listOf(jpg, cr3, mvi)) {
            assertThat(row.importStatus).isEqualTo(ImportStatus.DUPLICATE)
            assertThat(UnitKeys.isDetached(row.unitKey)).isFalse()
        }
        assertThat(jpg.isPrimary).isTrue()
        assertThat(cr3.isPrimary).isFalse()
        assertThat(cr3.unitKey).isEqualTo(jpg.unitKey)
        assertThat(mvi.isPrimary).isTrue()
        assertThat(h.kicks).isEqualTo(3) // 대표 사진 2 + 영상 1 분석
        for (rel in h.card.originals.keys) assertThat(h.card.file(rel).exists()).isFalse()
        h.assertSafetyInvariant()
    }

    @Test fun duplicateWithLiveOwnerAttachesNewRawToOwnerUnit() {
        h.card.put(jpgPath, jpg1, DEFAULT_MTIME)
        h.run(move = false)
        val owner = h.item(jpgPath)

        // 같은 내용의 JPG가 다른 수정 시각으로(PC를 거쳐 다시 넣은 카드 등) + 처음 보는 CR3
        h.card.put(jpgPath, jpg1, DEFAULT_MTIME + 30_000)
        h.card.put(cr3Path, cr31, DEFAULT_MTIME + 30_000)
        val outcome = h.run(move = true)
        assertThat(outcome.error).isNull()

        val dup = h.items().last { it.srcRelPath == jpgPath && it.importStatus == ImportStatus.DUPLICATE }
        assertThat(dup.isPrimary).isFalse() // 주인이 검토에 나오므로 두 번 나오지 않게
        assertThat(UnitKeys.isDetached(dup.unitKey)).isTrue()
        val cr3 = h.item(cr3Path)
        assertThat(cr3.unitKey).isEqualTo(owner.unitKey)
        assertThat(cr3.isPrimary).isFalse()
        assertThat(h.phoneFiles().keys).containsExactly("IMG_0001.JPG", "IMG_0001.CR3")
        h.assertSafetyInvariant()
    }

    // ------------------------------------------------------------ 8. 다른 열을 덮어쓰지 않기

    @Test fun importWritesKeepReviewAndAnalysisColumns() {
        h.card.put(jpgPath, jpg1)
        h.card.put(cr3Path, cr31)
        // 원본 삭제 직전에 분석과 사용자가 같은 행을 바꿨다
        h.card.beforeDelete = { file ->
            if (file.name == "IMG_0001.JPG") {
                h.db.openHelper.writableDatabase.execSQL(
                    """UPDATE media_item SET sharpness = 123.5, dhash = 42, groupId = 7, reviewState = 'TRASHED',
                       trashPath = '/trash/IMG_0001.JPG', trashedAt = 9 WHERE srcRelPath = '$jpgPath'""",
                )
            }
        }
        h.run(move = true)
        val jpg = h.item(jpgPath)
        assertThat(jpg.importStatus).isEqualTo(ImportStatus.SOURCE_DELETED)
        assertThat(jpg.sharpness).isEqualTo(123.5)
        assertThat(jpg.dhash).isEqualTo(42L)
        assertThat(jpg.groupId).isEqualTo(7L)
        assertThat(jpg.reviewState).isEqualTo(ReviewState.TRASHED)
        assertThat(jpg.trashPath).isEqualTo("/trash/IMG_0001.JPG")
        assertThat(jpg.trashedAt).isEqualTo(9L)
        h.assertSafetyInvariant()
    }

    @Test fun ledgerImportUpdatesTouchOnlyImportColumns() = runBlocking<Unit> {
        h.card.put(jpgPath, jpg1)
        h.run(move = false)
        val stale = h.item(jpgPath) // 이 시점의 사본(분석·검토 열은 비어 있음)
        h.db.openHelper.writableDatabase.execSQL(
            """UPDATE media_item SET sharpness = 9.5, focusX = 0.25, groupId = 3, reviewState = 'KEPT',
               trashPath = '/t', trashedAt = 4 WHERE id = ${stale.id}""",
        )

        h.ledger.updateImportFields(stale.copy(importStatus = ImportStatus.SOURCE_DELETE_FAILED, updatedAt = 1))
        assertThat(h.ledger.setStatusIf(stale.id, setOf(ImportStatus.VERIFIED), ImportStatus.SOURCE_DELETED, 2)).isFalse()
        assertThat(h.ledger.setStatusIf(stale.id, setOf(ImportStatus.SOURCE_DELETE_FAILED), ImportStatus.SOURCE_DELETED, 3)).isTrue()

        val after = h.db.mediaDao().get(stale.id)!!
        assertThat(after.importStatus).isEqualTo(ImportStatus.SOURCE_DELETED)
        assertThat(after.updatedAt).isEqualTo(3)
        assertThat(after.sharpness).isEqualTo(9.5)
        assertThat(after.focusX).isEqualTo(0.25f)
        assertThat(after.groupId).isEqualTo(3L)
        assertThat(after.reviewState).isEqualTo(ReviewState.KEPT)
        assertThat(after.trashPath).isEqualTo("/t")
        assertThat(after.trashedAt).isEqualTo(4L)
    }
}
