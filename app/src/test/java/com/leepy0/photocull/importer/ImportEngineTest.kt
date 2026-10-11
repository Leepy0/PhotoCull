package com.leepy0.photocull.importer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.data.db.ImportStatus
import com.leepy0.photocull.data.db.MediaType
import com.leepy0.photocull.data.db.SessionState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ImportEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var h: EngineHarness

    private val jpg1 = randomBytes(1, 300_000)
    private val cr31 = randomBytes(2, 700_000)
    private val jpg2 = randomBytes(3, 250_000)
    private val cr32 = randomBytes(4, 650_000)
    private val mp4 = randomBytes(5, 900_000)

    @Before fun setUp() {
        h = EngineHarness(tmp.root)
    }

    @After fun tearDown() {
        h.close()
    }

    /** 100CANON: IMG_0001(JPG+CR3), IMG_0002(JPG+CR3), MVI_0003.MP4 */
    private fun standardCard() {
        h.card.put("DCIM/100CANON/IMG_0001.JPG", jpg1, DEFAULT_MTIME)
        h.card.put("DCIM/100CANON/IMG_0001.CR3", cr31, DEFAULT_MTIME)
        h.card.put("DCIM/100CANON/IMG_0002.JPG", jpg2, DEFAULT_MTIME + 2_000)
        h.card.put("DCIM/100CANON/IMG_0002.CR3", cr32, DEFAULT_MTIME + 2_000)
        h.card.put("DCIM/100CANON/MVI_0003.MP4", mp4, DEFAULT_MTIME + 60_000)
    }

    private fun session(id: Long) = runBlocking { h.db.sessionDao().get(id)!! }

    @Test fun happyPathMove() {
        standardCard()
        // 건드리면 안 되는 파일: 숨김(macOS), 대상 아닌 확장자, DCIM 밖
        h.card.put("DCIM/100CANON/._IMG_0001.JPG", randomBytes(9, 100))
        h.card.put("DCIM/CANONMSC/M0001.CTG", randomBytes(10, 100))
        h.card.put("MISC/IMG_9999.JPG", randomBytes(11, 100))

        val progress = ArrayList<ImportProgress>()
        val outcome = h.run(move = true, progress = progress)

        assertThat(outcome.error).isNull()
        val r = outcome.result
        assertThat(r.savedCount).isEqualTo(5)
        assertThat(r.duplicateCount).isEqualTo(0)
        assertThat(r.failedCount).isEqualTo(0)
        assertThat(r.sourceDeleteFailedCount).isEqualTo(0)
        assertThat(r.photoCount).isEqualTo(2)
        assertThat(r.videoCount).isEqualTo(1)
        assertThat(r.savedBytes).isEqualTo((jpg1.size + cr31.size + jpg2.size + cr32.size + mp4.size).toLong())
        assertThat(r.cancelled).isFalse()

        // 폰: 같은 이름, 같은 내용, 원본 수정 시각
        val files = h.phoneFiles()
        assertThat(files.keys).containsExactly("IMG_0001.JPG", "IMG_0001.CR3", "IMG_0002.JPG", "IMG_0002.CR3", "MVI_0003.MP4")
        assertThat(sha256(files.getValue("IMG_0001.CR3"))).isEqualTo(sha256(cr31))
        assertThat(sha256(files.getValue("MVI_0003.MP4"))).isEqualTo(sha256(mp4))
        assertThat(files.getValue("MVI_0003.MP4").lastModified()).isEqualTo(DEFAULT_MTIME + 60_000)

        // 카드: 대상 파일만 지워짐
        for (rel in h.card.originals.keys) assertThat(h.card.file(rel).exists()).isFalse()
        assertThat(h.card.file("DCIM/100CANON/._IMG_0001.JPG").exists()).isTrue()
        assertThat(h.card.file("DCIM/CANONMSC/M0001.CTG").exists()).isTrue()
        assertThat(h.card.file("MISC/IMG_9999.JPG").exists()).isTrue()

        // DB: 상태, 단위 키, 대표 파일, 종류
        val sid = r.sessionId
        val jpg = h.item("DCIM/100CANON/IMG_0001.JPG")
        val cr3 = h.item("DCIM/100CANON/IMG_0001.CR3")
        val mvi = h.item("DCIM/100CANON/MVI_0003.MP4")
        assertThat(jpg.importStatus).isEqualTo(ImportStatus.SOURCE_DELETED)
        assertThat(cr3.importStatus).isEqualTo(ImportStatus.SOURCE_DELETED)
        assertThat(jpg.unitKey).isEqualTo("$sid:DCIM/100CANON/IMG_0001")
        assertThat(cr3.unitKey).isEqualTo(jpg.unitKey)
        assertThat(jpg.isPrimary).isTrue()
        assertThat(cr3.isPrimary).isFalse()
        assertThat(jpg.type).isEqualTo(MediaType.PHOTO)
        assertThat(cr3.type).isEqualTo(MediaType.RAW)
        assertThat(mvi.type).isEqualTo(MediaType.VIDEO)
        assertThat(mvi.isPrimary).isTrue()
        assertThat(jpg.mimeType).isEqualTo("image/jpeg")
        assertThat(mvi.mimeType).isEqualTo("video/mp4")
        assertThat(jpg.sha256).isEqualTo(sha256(jpg1))
        assertThat(jpg.dstPath).isEqualTo(File(h.dest, "IMG_0001.JPG").path)
        assertThat(jpg.capturedAt).isEqualTo(DEFAULT_MTIME) // EXIF 없음 → 수정 시각

        val s = session(sid)
        assertThat(s.state).isEqualTo(SessionState.COMPLETED)
        assertThat(s.finishedAt).isNotNull()
        assertThat(s.totalFiles).isEqualTo(5)
        assertThat(s.successCount).isEqualTo(5)

        // 갤러리 반영 5개, 분석 신호는 대표 사진 2 + 영상 1
        assertThat(h.scanned).hasSize(5)
        assertThat(h.kicks).isEqualTo(3)

        // 진행률: 마지막은 전부 완료
        val last = progress.last()
        assertThat(last.doneFiles).isEqualTo(5)
        assertThat(last.fraction).isEqualTo(1f)
        assertThat(last.sessionId).isEqualTo(sid)
        h.assertSafetyInvariant()
    }

    @Test fun copyModeKeepsSourcesAndRerunSkipsLedgerMatches() {
        standardCard()
        val first = h.run(move = false)
        assertThat(first.result.savedCount).isEqualTo(5)
        for (rel in h.card.originals.keys) assertThat(h.card.file(rel).exists()).isTrue()
        assertThat(h.item("DCIM/100CANON/IMG_0001.JPG").importStatus).isEqualTo(ImportStatus.VERIFIED)

        // 다시 복사: 이전 기록과 같으니 모두 건너뜀, 폰에 _1 사본이 생기지 않음
        val plan = h.plan()
        assertThat(plan.summary.alreadyImportedCount).isEqualTo(5)
        assertThat(plan.summary.newFileCount).isEqualTo(0)
        val second = h.run(move = false)
        assertThat(second.result.savedCount).isEqualTo(0)
        assertThat(second.result.duplicateCount).isEqualTo(5)
        assertThat(h.phoneFiles()).hasSize(5)
        for (rel in h.card.originals.keys) assertThat(h.card.file(rel).exists()).isTrue()
        h.assertSafetyInvariant()
    }

    @Test fun ledgerMatchInMoveModeDeletesSourceOnlyWhenPhoneCopyVerifies() {
        standardCard()
        h.run(move = false)
        // 사용자가 갤러리에서 폰 복사본 하나를 지움 → 그 원본은 카드에서 지우면 안 된다
        File(h.dest, "IMG_0002.JPG").delete()

        val outcome = h.run(move = true)
        assertThat(outcome.error).isNull()
        assertThat(outcome.result.savedCount).isEqualTo(0)
        assertThat(outcome.result.duplicateCount).isEqualTo(5)
        assertThat(h.card.file("DCIM/100CANON/IMG_0002.JPG").exists()).isTrue()
        for (rel in h.card.originals.keys - "DCIM/100CANON/IMG_0002.JPG") assertThat(h.card.file(rel).exists()).isFalse()
        assertThat(h.item("DCIM/100CANON/IMG_0001.JPG").importStatus).isEqualTo(ImportStatus.SOURCE_DELETED)
        assertThat(h.item("DCIM/100CANON/IMG_0002.JPG").importStatus).isEqualTo(ImportStatus.VERIFIED)
        assertThat(h.phoneFiles()).hasSize(4) // 새로 복사하지 않음
        h.assertSafetyInvariant()
    }

    @Test fun ledgerMatchWithTamperedPhoneCopyKeepsSource() {
        h.card.put("DCIM/100CANON/IMG_0001.JPG", jpg1)
        h.run(move = false)
        // 같은 크기, 다른 내용으로 바뀐 폰 파일
        File(h.dest, "IMG_0001.JPG").writeBytes(randomBytes(77, jpg1.size))
        h.run(move = true)
        assertThat(h.card.file("DCIM/100CANON/IMG_0001.JPG").exists()).isTrue()
        h.assertSafetyInvariant()
    }

    @Test fun nameCollisionWithIdenticalContentIsDuplicate() {
        h.card.put("DCIM/100CANON/IMG_0001.JPG", jpg1)
        h.card.put("DCIM/100CANON/IMG_0001.CR3", cr31)
        // 폰에 이미 같은 내용의 JPG(기록 없음, 예: 직접 복사)
        h.dest.mkdirs()
        File(h.dest, "IMG_0001.JPG").writeBytes(jpg1)

        val outcome = h.run(move = true)
        val r = outcome.result
        assertThat(r.duplicateCount).isEqualTo(1)
        assertThat(r.savedCount).isEqualTo(1)
        assertThat(h.phoneFiles().keys).containsExactly("IMG_0001.JPG", "IMG_0001.CR3")
        val dup = h.item("DCIM/100CANON/IMG_0001.JPG")
        assertThat(dup.importStatus).isEqualTo(ImportStatus.DUPLICATE)
        assertThat(dup.isPrimary).isFalse() // 검토에 두 번 나오지 않게
        assertThat(dup.dstPath).isEqualTo(File(h.dest, "IMG_0001.JPG").path)
        assertThat(dup.sha256).isEqualTo(sha256(jpg1))
        // 이동 모드: 같은 내용이 확인됐으므로 카드 원본 삭제
        assertThat(h.card.file("DCIM/100CANON/IMG_0001.JPG").exists()).isFalse()
        assertThat(h.card.file("DCIM/100CANON/IMG_0001.CR3").exists()).isFalse()
        h.assertSafetyInvariant()
    }

    @Test fun nameCollisionWithDifferentContentSuffixesWholeUnit() {
        h.card.put("DCIM/100CANON/IMG_0001.JPG", jpg1)
        h.card.put("DCIM/100CANON/IMG_0001.CR3", cr31)
        // 폰에 같은 이름, 다른 내용(이전 카드의 다른 사진). CR3 자리는 비어 있지만 단위 전체가 _1이어야 한다.
        val other = randomBytes(42, 123_456)
        h.dest.mkdirs()
        File(h.dest, "IMG_0001.JPG").writeBytes(other)

        val outcome = h.run(move = true)
        assertThat(outcome.result.savedCount).isEqualTo(2)
        assertThat(h.phoneFiles().keys).containsExactly("IMG_0001.JPG", "IMG_0001_1.JPG", "IMG_0001_1.CR3")
        assertThat(sha256(File(h.dest, "IMG_0001.JPG"))).isEqualTo(sha256(other)) // 기존 파일은 그대로
        assertThat(sha256(File(h.dest, "IMG_0001_1.JPG"))).isEqualTo(sha256(jpg1))
        assertThat(sha256(File(h.dest, "IMG_0001_1.CR3"))).isEqualTo(sha256(cr31))
        val jpg = h.item("DCIM/100CANON/IMG_0001.JPG")
        val cr3 = h.item("DCIM/100CANON/IMG_0001.CR3")
        assertThat(jpg.fileName).isEqualTo("IMG_0001_1.JPG")
        assertThat(cr3.fileName).isEqualTo("IMG_0001_1.CR3")
        assertThat(cr3.unitKey).isEqualTo(jpg.unitKey)
        h.assertSafetyInvariant()
    }

    @Test fun sameNameInTwoCameraFoldersKeepsPairsTogether() {
        h.card.put("DCIM/100CANON/IMG_0001.JPG", jpg1)
        h.card.put("DCIM/100CANON/IMG_0001.CR3", cr31)
        h.card.put("DCIM/101CANON/IMG_0001.JPG", jpg2)
        h.card.put("DCIM/101CANON/IMG_0001.CR3", cr32)

        h.run(move = true)
        val files = h.phoneFiles()
        assertThat(files.keys).containsExactly("IMG_0001.JPG", "IMG_0001.CR3", "IMG_0001_1.JPG", "IMG_0001_1.CR3")
        assertThat(sha256(files.getValue("IMG_0001_1.JPG"))).isEqualTo(sha256(jpg2))
        assertThat(sha256(files.getValue("IMG_0001_1.CR3"))).isEqualTo(sha256(cr32))
        h.assertSafetyInvariant()
    }

    @Test fun rawOnlyShotDoesNotPairWithUnrelatedJpg() {
        // 다른 촬영의 IMG_0001.JPG가 이미 폰에 있고, 이번 카드는 RAW만 찍음 → IMG_0001.CR3로 두면 짝처럼 보인다
        h.dest.mkdirs()
        File(h.dest, "IMG_0001.JPG").writeBytes(randomBytes(50, 1000))
        h.card.put("DCIM/100CANON/IMG_0001.CR3", cr31)
        h.run(move = true)
        assertThat(h.phoneFiles().keys).containsExactly("IMG_0001.JPG", "IMG_0001_1.CR3")
        val cr3 = h.item("DCIM/100CANON/IMG_0001.CR3")
        assertThat(cr3.isPrimary).isTrue()
        assertThat(cr3.type).isEqualTo(MediaType.RAW)
        h.assertSafetyInvariant()
    }

    @Test fun verifyFailureFailsFileAndKeepsSource() {
        standardCard()
        // IMG_0002.JPG는 쓸 때마다 깨진다(재시도 포함)
        val io = CorruptingFiles { name, _ -> name == "IMG_0002.JPG" }
        val outcome = h.run(move = true, io = io)

        assertThat(io.attempts["IMG_0002.JPG"]).isEqualTo(2) // 1회 재시도
        val r = outcome.result
        assertThat(outcome.error).isNull()
        assertThat(r.failedCount).isEqualTo(1)
        assertThat(r.failures.single().reason).isEqualTo(FailureReason.VERIFY_FAILED)
        assertThat(r.failures.single().fileName).isEqualTo("100CANON/IMG_0002.JPG")
        // 실패 파일: 원본 유지, 최종 파일·.part 없음
        assertThat(h.card.file("DCIM/100CANON/IMG_0002.JPG").exists()).isTrue()
        assertThat(File(h.dest, "IMG_0002.JPG").exists()).isFalse()
        val failed = h.item("DCIM/100CANON/IMG_0002.JPG")
        assertThat(failed.importStatus).isEqualTo(ImportStatus.FAILED)
        assertThat(failed.dstPath).isNull()
        assertThat(failed.isPrimary).isFalse()
        assertThat(UnitKeys.isDetached(failed.unitKey)).isTrue()
        // 나머지는 정상 진행. 같은 단위의 CR3는 검증됐으므로 이동
        assertThat(r.savedCount).isEqualTo(4)
        assertThat(h.card.file("DCIM/100CANON/IMG_0002.CR3").exists()).isFalse()
        assertThat(h.card.file("DCIM/100CANON/MVI_0003.MP4").exists()).isFalse()
        assertThat(session(r.sessionId).failedCount).isEqualTo(1)
        h.assertSafetyInvariant()

        // 다시 가져오면 실패했던 JPG만 대상이 되고 기존 단위에 이어 붙는다
        val retry = h.run(move = true)
        assertThat(retry.result.savedCount).isEqualTo(1)
        val again = h.item("DCIM/100CANON/IMG_0002.JPG")
        assertThat(again.importStatus).isEqualTo(ImportStatus.SOURCE_DELETED)
        assertThat(again.unitKey).isEqualTo(h.item("DCIM/100CANON/IMG_0002.CR3").unitKey)
        assertThat(again.isPrimary).isTrue()
        assertThat(h.phoneFiles().keys).containsExactly("IMG_0001.JPG", "IMG_0001.CR3", "IMG_0002.JPG", "IMG_0002.CR3", "MVI_0003.MP4")
        h.assertSafetyInvariant()
    }

    @Test fun verifyFailureOnceSucceedsOnRetry() {
        h.card.put("DCIM/100CANON/IMG_0001.JPG", jpg1)
        val io = CorruptingFiles { name, attempt -> name == "IMG_0001.JPG" && attempt == 1 }
        val outcome = h.run(move = true, io = io)
        assertThat(outcome.result.savedCount).isEqualTo(1)
        assertThat(outcome.result.failedCount).isEqualTo(0)
        assertThat(sha256(File(h.dest, "IMG_0001.JPG"))).isEqualTo(sha256(jpg1))
        assertThat(h.card.file("DCIM/100CANON/IMG_0001.JPG").exists()).isFalse()
        h.assertSafetyInvariant()
    }

    @Test fun sourceDeleteFailureKeepsCopyAndReports() {
        standardCard()
        h.card.failDeletes += "IMG_0002.JPG"
        val outcome = h.run(move = true)
        val r = outcome.result
        assertThat(r.savedCount).isEqualTo(5)
        assertThat(r.sourceDeleteFailedCount).isEqualTo(1)
        assertThat(r.failedCount).isEqualTo(0)
        assertThat(r.failures.single().reason).isEqualTo(FailureReason.SOURCE_DELETE_FAILED)
        assertThat(h.item("DCIM/100CANON/IMG_0002.JPG").importStatus).isEqualTo(ImportStatus.SOURCE_DELETE_FAILED)
        assertThat(sha256(File(h.dest, "IMG_0002.JPG"))).isEqualTo(sha256(jpg2))
        assertThat(h.card.file("DCIM/100CANON/IMG_0002.JPG").exists()).isTrue()
        assertThat(session(r.sessionId).sourceDeleteFailedCount).isEqualTo(1)
        h.assertSafetyInvariant()

        // 쓰기 금지를 풀고 다시 이동: 기록과 같은 파일이라 복사 없이 원본만 지운다
        h.card.failDeletes.clear()
        val again = h.run(move = true)
        assertThat(again.result.savedCount).isEqualTo(0)
        assertThat(h.card.file("DCIM/100CANON/IMG_0002.JPG").exists()).isFalse()
        assertThat(h.item("DCIM/100CANON/IMG_0002.JPG").importStatus).isEqualTo(ImportStatus.SOURCE_DELETED)
        h.assertSafetyInvariant()
    }

    @Test fun cancelMidwayStopsAndCleansUp() {
        standardCard()
        val cancel = CancelSignal()
        // EXIF 헤더 읽기(64KB 한 번)를 지나 실제 복사 중(.part가 있는 상태)에 취소
        var partSeen = false
        h.card.onRead = { file, bytes ->
            if (file.name == "IMG_0002.JPG" && bytes >= 128 * 1024 && !cancel.isCancelled) {
                partSeen = h.partFiles().isNotEmpty()
                cancel.cancel()
            }
        }
        val outcome = h.run(move = true, cancel = cancel)

        val r = outcome.result
        assertThat(partSeen).isTrue()
        assertThat(outcome.error).isNull()
        assertThat(r.cancelled).isTrue()
        assertThat(r.savedCount).isEqualTo(2)
        assertThat(session(r.sessionId).state).isEqualTo(SessionState.CANCELLED)
        // 이미 옮긴 단위는 유지, 나머지는 카드에 그대로
        assertThat(h.phoneFiles().keys).containsExactly("IMG_0001.JPG", "IMG_0001.CR3")
        assertThat(h.card.file("DCIM/100CANON/IMG_0001.JPG").exists()).isFalse()
        assertThat(h.card.file("DCIM/100CANON/IMG_0002.JPG").exists()).isTrue()
        assertThat(h.card.file("DCIM/100CANON/IMG_0002.CR3").exists()).isTrue()
        assertThat(h.card.file("DCIM/100CANON/MVI_0003.MP4").exists()).isTrue()
        val cancelled = h.item("DCIM/100CANON/IMG_0002.JPG")
        assertThat(cancelled.importStatus).isEqualTo(ImportStatus.FAILED)
        assertThat(cancelled.importError).isEqualTo("CANCELLED")
        assertThat(cancelled.dstPath).isNull()
        assertThat(r.failures).isEmpty() // 취소는 실패로 세지 않는다
        h.assertSafetyInvariant()

        // 다시 가져오면 나머지만
        h.card.onRead = null
        val rest = h.run(move = true)
        assertThat(rest.result.savedCount).isEqualTo(3)
        assertThat(h.phoneFiles()).hasSize(5)
        h.assertSafetyInvariant()
    }

    @Test fun cardRemovalMidCopyStopsAndResumesWithOnlyTheRest() {
        standardCard()
        h.card.removeWhileReading = "IMG_0002.CR3"
        h.card.removeAfterBytes = 100_000
        val outcome = h.run(move = true)

        val error = outcome.error
        assertThat(error).isInstanceOf(ImportError.CardRemoved::class.java)
        error as ImportError.CardRemoved
        assertThat(error.totalFiles).isEqualTo(5)
        assertThat(error.doneFiles).isEqualTo(3) // IMG_0001.JPG, IMG_0001.CR3, IMG_0002.JPG
        assertThat(session(outcome.result.sessionId).state).isEqualTo(SessionState.INTERRUPTED)
        assertThat(h.partFiles()).isEmpty()
        assertThat(File(h.dest, "IMG_0002.CR3").exists()).isFalse()
        // 단위 처리가 끝나기 전에 빠졌으므로 IMG_0002.JPG는 검증만 됐고 카드에 남아 있다
        assertThat(h.item("DCIM/100CANON/IMG_0002.JPG").importStatus).isEqualTo(ImportStatus.VERIFIED)
        assertThat(h.card.file("DCIM/100CANON/IMG_0002.JPG").exists()).isTrue()
        assertThat(h.card.file("DCIM/100CANON/IMG_0001.JPG").exists()).isFalse()
        val interrupted = h.item("DCIM/100CANON/IMG_0002.CR3")
        assertThat(interrupted.importStatus).isEqualTo(ImportStatus.FAILED)
        assertThat(interrupted.importError).isEqualTo("CARD_REMOVED")
        h.assertSafetyInvariant()
        val firstUnitKey = h.item("DCIM/100CANON/IMG_0002.JPG").unitKey

        // 다시 연결: 남은 파일만 대상
        h.card.removeWhileReading = null
        h.card.present = true
        val plan = h.plan()
        assertThat(plan.summary.newFileCount).isEqualTo(2) // IMG_0002.CR3, MVI_0003.MP4
        assertThat(plan.summary.alreadyImportedCount).isEqualTo(1) // IMG_0002.JPG
        val resumed = h.run(move = true)
        assertThat(resumed.error).isNull()
        assertThat(resumed.result.savedCount).isEqualTo(2)

        assertThat(h.phoneFiles().keys).containsExactly("IMG_0001.JPG", "IMG_0001.CR3", "IMG_0002.JPG", "IMG_0002.CR3", "MVI_0003.MP4")
        for (rel in h.card.originals.keys) assertThat(h.card.file(rel).exists()).isFalse()
        // 이어 가져온 CR3는 처음 세션의 단위에 붙고 대표가 아니다
        val cr3 = h.items().last { it.srcRelPath == "DCIM/100CANON/IMG_0002.CR3" && it.importStatus == ImportStatus.SOURCE_DELETED }
        assertThat(cr3.unitKey).isEqualTo(firstUnitKey)
        assertThat(cr3.isPrimary).isFalse()
        assertThat(h.item("DCIM/100CANON/IMG_0002.JPG").importStatus).isEqualTo(ImportStatus.SOURCE_DELETED)
        h.assertSafetyInvariant()
    }

    @Test fun cardRemovedBeforeAnyCopyReportsZeroDone() {
        h.card.put("DCIM/100CANON/IMG_0001.JPG", jpg1)
        h.card.removeWhileReading = "IMG_0001.JPG"
        h.card.removeAfterBytes = 0
        val outcome = h.run(move = true)
        assertThat(outcome.error).isEqualTo(ImportError.CardRemoved(0, 1))
        assertThat(h.phoneFiles()).isEmpty()
        h.assertSafetyInvariant()
    }

    @Test fun stalePartFilesAreCleanedBeforeImport() {
        h.dest.mkdirs()
        val stale = File(h.dest, "IMG_0500.JPG.photocull.part").apply { writeBytes(randomBytes(1, 10)) }
        val nested = File(h.dest, "2026-09/IMG_0400.CR3.photocull.part").apply { parentFile!!.mkdirs(); writeBytes(randomBytes(2, 10)) }
        val keepPart = File(h.dest, "download.part").apply { writeBytes(randomBytes(3, 10)) }
        val keepJpg = File(h.dest, "IMG_0300.JPG").apply { writeBytes(randomBytes(4, 10)) }
        h.card.put("DCIM/100CANON/IMG_0001.JPG", jpg1)

        h.run(move = true)
        assertThat(stale.exists()).isFalse()
        assertThat(nested.exists()).isFalse()
        assertThat(keepPart.exists()).isTrue()
        assertThat(keepJpg.exists()).isTrue()
        h.assertSafetyInvariant()
    }

    @Test fun monthlySubfoldersUseExifCaptureDateForWholeUnit() {
        // EXIF는 2025년 3월, 파일 수정 시각은 2026년 10월 → EXIF 기준
        val jpg = jpegWithExif("2025:03:14 12:00:00", randomBytes(20, 50_000))
        h.card.put("DCIM/100CANON/IMG_0001.JPG", jpg, DEFAULT_MTIME)
        h.card.put("DCIM/100CANON/IMG_0001.CR3", cr31, DEFAULT_MTIME)
        // 영상은 수정 시각(2026년 1월 중순)
        val videoTime = LocalDateTime.of(2026, 1, 15, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        h.card.put("DCIM/100CANON/MVI_0002.MP4", mp4, videoTime)

        val outcome = h.run(move = true, monthly = true)
        assertThat(outcome.error).isNull()
        assertThat(h.phoneFiles().keys).containsExactly(
            "2025-03/IMG_0001.JPG",
            "2025-03/IMG_0001.CR3",
            "2026-01/MVI_0002.MP4",
        )
        val expected = LocalDateTime.of(2025, 3, 14, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertThat(h.item("DCIM/100CANON/IMG_0001.JPG").capturedAt).isEqualTo(expected)
        assertThat(h.item("DCIM/100CANON/IMG_0001.CR3").capturedAt).isEqualTo(expected)
        h.assertSafetyInvariant()
    }

    @Test fun insufficientSpaceDuringImportStopsWithoutLosingAnything() {
        standardCard()
        var free = Long.MAX_VALUE
        val engine = ImportEngine(
            ledger = h.ledger,
            freeSpace = { free },
            removalGraceMs = 0,
        )
        var copied = 0
        h.card.onRead = { file, bytes -> if (bytes == file.size) copied++; if (copied >= 2) free = 10L * 1024 * 1024 }
        val outcome = runBlocking { engine.run(ImportRequest(h.plan(), true, "R10", false, h.phone), h.card) }
        assertThat(outcome.error).isInstanceOf(ImportError.InsufficientSpace::class.java)
        assertThat(session(outcome.result.sessionId).state).isEqualTo(SessionState.INTERRUPTED)
        h.assertSafetyInvariant()
    }
}
