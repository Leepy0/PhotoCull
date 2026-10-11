package com.leepy0.photocull.ui.importer

import android.content.Intent
import android.net.Uri
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.data.review.ReviewCounts
import com.leepy0.photocull.data.settings.AppSettings
import com.leepy0.photocull.importer.CardScanSummary
import com.leepy0.photocull.importer.FailureReason
import com.leepy0.photocull.importer.ImportFailure
import com.leepy0.photocull.importer.ImportProgress
import com.leepy0.photocull.importer.ImportResult
import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.storage.CardAccess
import com.leepy0.photocull.storage.CardState
import com.leepy0.photocull.storage.CardVolume
import com.leepy0.photocull.ui.PHONE
import com.leepy0.photocull.ui.UNFOLDED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 실제 기기처럼 한국어 로캘(줄바꿈이 어절 단위로 되는지까지 본다) */
const val KO_PHONE = "ko-rKR-$PHONE"
const val KO_UNFOLDED = "ko-rKR-$UNFOLDED"

const val MB = 1024L * 1024L
const val GB = 1024L * MB
const val DAY_MS = 86_400_000L

object ImportFakes {
    val now: Long = System.currentTimeMillis()

    val volume = CardVolume(
        id = "3A1B-27F0",
        label = "EOS_DIGITAL",
        rootPath = "/storage/3A1B-27F0",
        treeUri = null,
        access = CardAccess.DIRECT,
    )

    fun summary(
        newFiles: Int = 300,
        newBytes: Long = 12_900 * MB,
        already: Int = 156,
        freeBytes: Long = 118 * GB,
    ) = CardScanSummary(
        volume = volume,
        photoCount = 324,
        rawCount = 120,
        videoCount = 12,
        fileCount = newFiles + already,
        totalBytes = 18_840 * MB,
        alreadyImportedCount = already,
        newFileCount = newFiles,
        newBytes = newBytes,
        freeBytes = freeBytes,
        requiredBytes = newBytes + 2 * GB,
        destDisplayPath = "DCIM/R10",
        oldestCapturedAt = now - 8 * DAY_MS,
        newestCapturedAt = now,
    )

    fun progress(
        done: Int = 120,
        doneBytes: Long = 5_160 * MB,
        speed: Long = 85 * MB,
        eta: Long? = 95,
        failed: Int = 0,
        current: String? = "100CANON/IMG_0123.CR3",
    ) = ImportProgress(
        sessionId = 7,
        totalFiles = 300,
        doneFiles = done,
        totalBytes = 12_900 * MB,
        doneBytes = doneBytes,
        currentFileName = current,
        bytesPerSecond = speed,
        etaSeconds = eta,
        failedCount = failed,
        startedAt = now - 60_000,
    )

    /** 서비스 시작 직후(진행률 0) */
    val preparing = progress(done = 0, doneBytes = 0, speed = 0, eta = null, current = null)

    fun result(
        saved: Int = 296,
        failed: Int = 2,
        deleteFailed: Int = 2,
        cancelled: Boolean = false,
        moveMode: Boolean = true,
    ) = ImportResult(
        sessionId = 7,
        moveMode = moveMode,
        savedCount = saved,
        duplicateCount = 156,
        failedCount = failed,
        sourceDeleteFailedCount = deleteFailed,
        savedBytes = 12_700 * MB,
        durationMs = 252_000,
        cancelled = cancelled,
        failures = buildList {
            if (failed > 0) add(ImportFailure("100CANON/IMG_0002.JPG", FailureReason.VERIFY_FAILED, null))
            if (failed > 1) add(ImportFailure("100CANON/IMG_0187.CR3", FailureReason.READ_ERROR, null))
            if (deleteFailed > 0) add(ImportFailure("100CANON/IMG_0201.JPG", FailureReason.SOURCE_DELETE_FAILED, null))
            if (deleteFailed > 1) add(ImportFailure("100CANON/IMG_0201.CR3", FailureReason.SOURCE_DELETE_FAILED, null))
        },
        photoCount = 180,
        videoCount = 12,
        destDisplayPath = "DCIM/R10",
    )

    val counts = ReviewCounts(blur = 12, groups = 8, groupPhotos = 64, videos = 5, shortVideos = 2)
}

/** 메모리에서 동작하는 가짜 컨트롤러/카드 모니터. 호출 기록을 남긴다. */
open class FakeImportFlowSource(
    card: CardState = CardState.NotConnected,
    import: ImportState = ImportState.Idle,
) : ImportFlowSource {
    val cardFlow = MutableStateFlow(card)
    val importFlow = MutableStateFlow(import)
    val analysisFlow = MutableStateFlow(AnalysisStatus())
    val countsFlow = MutableStateFlow(ReviewCounts())
    val settingsFlow = MutableStateFlow(AppSettings())

    var scans = 0
    val starts = mutableListOf<Boolean>()
    var cancels = 0
    var resets = 0
    var refreshes = 0

    /** scan()이 부르면 바로 이 상태로 바뀐다(null이면 Scanning) */
    var scanResult: ImportState? = null

    override val cardState: StateFlow<CardState> get() = cardFlow
    override val importState: StateFlow<ImportState> get() = importFlow
    override val analysis: StateFlow<AnalysisStatus> get() = analysisFlow
    override fun reviewCounts(): Flow<ReviewCounts> = countsFlow
    override fun settings(): Flow<AppSettings> = settingsFlow

    override fun scan() {
        scans++
        importFlow.value = scanResult ?: ImportState.Scanning
    }

    override fun start(moveMode: Boolean) {
        starts += moveMode
    }

    override fun cancel() {
        cancels++
    }

    override fun reset() {
        resets++
        importFlow.value = ImportState.Idle
    }

    override fun refreshCard() {
        refreshes++
    }

    override fun createAccessIntent(volumeId: String): Intent? = null
    override fun onAccessGranted(volumeId: String, treeUri: Uri) = Unit
}
