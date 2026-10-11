package com.leepy0.photocull.ui.importer

import androidx.compose.runtime.Immutable
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.data.review.ReviewCounts
import com.leepy0.photocull.importer.CardScanSummary
import com.leepy0.photocull.importer.FailureReason
import com.leepy0.photocull.importer.ImportError
import com.leepy0.photocull.importer.ImportFailure
import com.leepy0.photocull.importer.ImportProgress
import com.leepy0.photocull.importer.ImportResult
import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.storage.CardState

/** 가져오기 화면 상태(화면에 그릴 것은 [view]) */
@Immutable
data class ImportUiState(
    val import: ImportState = ImportState.Idle,
    val card: CardState = CardState.NotConnected,
    /** 이번 가져오기의 카드 원본 처리. true = 옮긴 뒤 삭제 */
    val moveMode: Boolean = true,
    /** 결과 화면의 [검토 시작] 문구. 아직 모르면 null */
    val counts: ReviewCounts? = null,
    val analysis: AnalysisStatus = AnalysisStatus(),
    /** [취소]를 눌러 멈추기를 기다리는 중 */
    val cancelRequested: Boolean = false,
) {
    val view: ImportView get() = ImportView.of(import, card)
}

/** 가져오기 화면이 보여줄 단계. 카드 상태와 가져오기 상태를 합쳐 하나로 정한다. */
sealed interface ImportView {
    /** 카드를 훑는 중(또는 곧 훑음) */
    data object Checking : ImportView

    /** 카드가 없음. [notCameraLabel]이 있으면 저장소는 연결됐지만 카메라 카드가 아님 */
    data class NoCard(val notCameraLabel: String? = null) : ImportView

    /** 파일 경로로 읽을 수 없어 카드 폴더 승인(SAF)이 필요함 */
    data class NeedsFolderAccess(val volumeId: String, val label: String) : ImportView

    /** 모든 파일 접근 권한이 없음 */
    data object NeedsPermission : ImportView

    data class Ready(val summary: CardScanSummary) : ImportView
    data class Running(val progress: ImportProgress) : ImportView
    data class Finished(val result: ImportResult) : ImportView
    data class Failed(val error: ImportError) : ImportView

    companion object {
        fun of(import: ImportState, card: CardState): ImportView = when (import) {
            ImportState.Scanning -> Checking
            is ImportState.Ready -> Ready(import.summary)
            is ImportState.Running -> Running(import.progress)
            is ImportState.Finished -> Finished(import.result)
            ImportState.Idle -> fromCard(card)
            is ImportState.Failed -> when (val error = import.error) {
                // 컨트롤러는 폴더 승인 필요도 NoPermission으로 알린다 → 카드 상태로 구분해 맞는 해결 방법을 보여준다
                ImportError.NoPermission -> when (card) {
                    is CardState.NeedsAccess -> NeedsFolderAccess(card.volumeId, card.label)
                    CardState.NoPermission -> NeedsPermission
                    else -> Failed(error)
                }
                // 카드가 빠진 상태면 오류보다 "연결하세요" 안내가 맞다
                ImportError.NoCard -> when (card) {
                    CardState.NotConnected -> NoCard()
                    is CardState.NotCameraCard -> NoCard(card.label)
                    CardState.NoPermission -> NeedsPermission
                    is CardState.NeedsAccess -> NeedsFolderAccess(card.volumeId, card.label)
                    is CardState.Ready -> Failed(error)
                }
                else -> Failed(error)
            }
        }

        private fun fromCard(card: CardState): ImportView = when (card) {
            CardState.NoPermission -> NeedsPermission
            CardState.NotConnected -> NoCard()
            is CardState.NotCameraCard -> NoCard(card.label)
            is CardState.NeedsAccess -> NeedsFolderAccess(card.volumeId, card.label)
            is CardState.Ready -> Checking
        }
    }
}

/** 서비스가 아직 복사를 시작하지 않음(시작 직후 0 진행률): "0%, 남은 시간 모름" 대신 준비 중으로 보여준다 */
val ImportProgress.isPreparing: Boolean
    get() = doneFiles == 0 && doneBytes == 0L && bytesPerSecond == 0L

/** 0..100 */
val ImportProgress.percent: Int get() = (fraction * 100).toInt().coerceIn(0, 100)

/** 결과의 실패 목록: 복사 실패(카드에 그대로 있음)와 카드 삭제 실패(폰에는 저장됨)를 나눈다 */
val ImportResult.copyFailures: List<ImportFailure>
    get() = failures.filter { it.reason != FailureReason.SOURCE_DELETE_FAILED }

val ImportResult.deleteFailures: List<ImportFailure>
    get() = failures.filter { it.reason == FailureReason.SOURCE_DELETE_FAILED }

val ImportResult.hasProblems: Boolean get() = failedCount > 0 || sourceDeleteFailedCount > 0

/** 검토로 보낼 만한 것이 있는지(분석 중이면 곧 생긴다) */
fun ReviewCounts?.hasWork(analysis: AnalysisStatus): Boolean = analysis.running || (this != null && total > 0)

/** 시:분:초 → 화면 표시용 조각 */
data class DurationParts(val hours: Long, val minutes: Long, val seconds: Long)

fun durationParts(ms: Long): DurationParts {
    val total = (ms.coerceAtLeast(0) + 500) / 1000
    return DurationParts(total / 3600, (total % 3600) / 60, total % 60)
}
