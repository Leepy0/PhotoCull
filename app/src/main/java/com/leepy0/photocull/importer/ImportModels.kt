package com.leepy0.photocull.importer

import com.leepy0.photocull.storage.CardVolume
import kotlinx.coroutines.flow.StateFlow

/** 가져오기 전 요약. 카드를 훑어 대상 파일과 필요한 공간을 계산한 결과 */
data class CardScanSummary(
    val volume: CardVolume,
    /** JPG/HIF 사진 수(대표 파일 기준) */
    val photoCount: Int,
    /** CR3 파일 수 */
    val rawCount: Int,
    val videoCount: Int,
    /** 대상 파일 전체 수(사진+RAW+영상) */
    val fileCount: Int,
    val totalBytes: Long,
    /** 이전에 가져온 기록과 같은 파일 수. 복사하지 않고 건너뛴다(이동 모드면 카드 원본만 삭제). */
    val alreadyImportedCount: Int,
    val newFileCount: Int,
    val newBytes: Long,
    /** 폰 여유 공간 */
    val freeBytes: Long,
    /** newBytes + 여유분(2GB) */
    val requiredBytes: Long,
    /** "DCIM/R10" */
    val destDisplayPath: String,
    val oldestCapturedAt: Long?,
    val newestCapturedAt: Long?,
) {
    val enoughSpace: Boolean get() = freeBytes >= requiredBytes
    val shortageBytes: Long get() = (requiredBytes - freeBytes).coerceAtLeast(0)
}

data class ImportProgress(
    val sessionId: Long,
    val totalFiles: Int,
    val doneFiles: Int,
    val totalBytes: Long,
    val doneBytes: Long,
    val currentFileName: String?,
    /** 최근 몇 초 기준 전송 속도. 아직 모르면 0 */
    val bytesPerSecond: Long,
    /** 남은 시간(초). 모르면 null */
    val etaSeconds: Long?,
    val failedCount: Int,
    val startedAt: Long,
) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

enum class FailureReason {
    /** 복사본과 원본 해시 불일치(재시도 후에도) */
    VERIFY_FAILED,
    READ_ERROR,
    WRITE_ERROR,
    /** 폰에는 저장됐으나 카드에서 삭제 못 함(쓰기 금지 스위치 등) */
    SOURCE_DELETE_FAILED,
}

data class ImportFailure(val fileName: String, val reason: FailureReason, val detail: String?)

data class ImportResult(
    val sessionId: Long,
    val moveMode: Boolean,
    /** 새로 저장한 파일 수 */
    val savedCount: Int,
    val duplicateCount: Int,
    val failedCount: Int,
    val sourceDeleteFailedCount: Int,
    val savedBytes: Long,
    val durationMs: Long,
    val cancelled: Boolean,
    val failures: List<ImportFailure>,
    /** 사진(대표) 수 / 영상 수 - 결과 화면 표시용 */
    val photoCount: Int,
    val videoCount: Int,
    val destDisplayPath: String,
)

sealed interface ImportError {
    data object NoCard : ImportError
    data object NoPermission : ImportError
    /** 가져오는 중 카드가 분리됨. 다음 연결 때 남은 파일부터 이어서 가져온다. */
    data class CardRemoved(val doneFiles: Int, val totalFiles: Int) : ImportError
    data class InsufficientSpace(val requiredBytes: Long, val freeBytes: Long) : ImportError
    data class Unexpected(val message: String) : ImportError
}

sealed interface ImportState {
    data object Idle : ImportState
    data object Scanning : ImportState
    data class Ready(val summary: CardScanSummary) : ImportState
    data class Running(val progress: ImportProgress) : ImportState
    data class Finished(val result: ImportResult) : ImportState
    data class Failed(val error: ImportError) : ImportState
}

/** 가져오기 상태 보관 + 시작/취소. 실제 작업은 ImportService(포그라운드 서비스)에서 한다. */
interface ImportController {
    val state: StateFlow<ImportState>

    /** 현재 연결된 카드를 훑어 [ImportState.Ready] 또는 [ImportState.Failed]로 전환 */
    fun scan()

    /** [ImportState.Ready]에서 가져오기 시작. moveMode = 검증 후 카드 원본 삭제 */
    fun start(moveMode: Boolean)

    fun cancel()

    /** 결과/오류 화면을 닫고 Idle로 */
    fun reset()
}
