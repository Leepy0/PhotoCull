package com.leepy0.photocull.ui.home

import androidx.compose.runtime.Immutable
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.data.db.ImportSessionEntity
import com.leepy0.photocull.data.db.SessionState
import com.leepy0.photocull.data.review.ReviewCounts
import com.leepy0.photocull.importer.CardScanSummary
import com.leepy0.photocull.importer.ImportError
import com.leepy0.photocull.importer.ImportProgress
import com.leepy0.photocull.importer.ImportResult
import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.storage.CardState
import com.leepy0.photocull.ui.components.UiState
import com.leepy0.photocull.ui.nav.ReviewTab

/** 홈 화면 상태 */
@Immutable
data class HomeUiState(
    val card: CardState = CardState.NotConnected,
    val import: ImportState = ImportState.Idle,
    /** 검토 대기 건수. Loading → Content | Error */
    val review: UiState<ReviewCounts> = UiState.Loading,
    val analysis: AnalysisStatus = AnalysisStatus(),
    val lastImport: LastImport? = null,
    /** 마지막 가져오기 기록을 불러왔는지(불러오기 전에는 '처음 사용' 안내를 띄우지 않는다) */
    val lastImportLoaded: Boolean = false,
    val updateAvailable: Boolean = false,
) {
    val status: HomeCardStatus get() = HomeCardStatus.of(card, import)
    val counts: ReviewCounts? get() = (review as? UiState.Content)?.data
    val primary: HomePrimary get() = HomePrimary.of(status, counts)

    /** 방금 끝난 결과를 카드 상태에서 보여주는 동안에는 같은 기록을 아래에 또 보여주지 않는다 */
    val visibleLastImport: LastImport? get() = if (status is HomeCardStatus.Finished) null else lastImport
}

/** 마지막으로 끝난 가져오기(DB 기록) */
@Immutable
data class LastImport(
    val finishedAt: Long,
    val volumeLabel: String,
    val moveMode: Boolean,
    val savedCount: Int,
    val duplicateCount: Int,
    val failedCount: Int,
    val sourceDeleteFailedCount: Int,
    val state: SessionState,
) {
    companion object {
        fun from(e: ImportSessionEntity): LastImport? = e.finishedAt?.let { finished ->
            LastImport(
                finishedAt = finished,
                volumeLabel = e.volumeLabel,
                moveMode = e.moveMode,
                savedCount = e.successCount,
                duplicateCount = e.duplicateCount,
                failedCount = e.failedCount,
                sourceDeleteFailedCount = e.sourceDeleteFailedCount,
                state = e.state,
            )
        }
    }
}

/**
 * 홈 맨 위 카드 상태 카드가 보여줄 것. 카드 상태와 가져오기 상태를 합쳐 하나로 정한다.
 * 우선순위: 가져오는 중 > 권한 없음 > 폴더 승인 필요 > 방금 끝난 결과 > 카드 상태(미연결·카메라 카드 아님·확인 중·요약·오류)
 */
sealed interface HomeCardStatus {
    data object NoPermission : HomeCardStatus
    data class NeedsAccess(val volumeId: String, val label: String) : HomeCardStatus

    /** [interrupted]: 지난 가져오기가 카드 분리로 멈춤(다시 연결하면 이어서) */
    data class NotConnected(val interrupted: ImportError.CardRemoved? = null) : HomeCardStatus
    data class NotCameraCard(val label: String) : HomeCardStatus
    data class Checking(val label: String) : HomeCardStatus
    data class CardReady(val summary: CardScanSummary) : HomeCardStatus
    data class Importing(val progress: ImportProgress, val label: String?) : HomeCardStatus
    data class Finished(val result: ImportResult) : HomeCardStatus

    /** 카드는 연결돼 있으나 확인·가져오기가 실패함 */
    data class Failed(val error: ImportError, val label: String) : HomeCardStatus

    companion object {
        fun of(card: CardState, import: ImportState): HomeCardStatus {
            if (import is ImportState.Running) return Importing(import.progress, (card as? CardState.Ready)?.volume?.label)
            when (card) {
                CardState.NoPermission -> return NoPermission
                is CardState.NeedsAccess -> return NeedsAccess(card.volumeId, card.label)
                else -> Unit
            }
            if (import is ImportState.Finished) return Finished(import.result)
            return when (card) {
                CardState.NotConnected ->
                    NotConnected(((import as? ImportState.Failed)?.error as? ImportError.CardRemoved))
                is CardState.NotCameraCard -> NotCameraCard(card.label)
                is CardState.Ready -> when (import) {
                    is ImportState.Ready ->
                        if (import.summary.volume.id == card.volume.id) CardReady(import.summary) else Checking(card.volume.label)
                    is ImportState.Failed -> Failed(import.error, card.volume.label)
                    else -> Checking(card.volume.label)
                }
                // 위에서 처리함
                CardState.NoPermission, is CardState.NeedsAccess -> error("unreachable")
            }
        }
    }
}

/** 홈 하단의 주요 행동(화면당 하나) */
sealed interface HomePrimary {
    /** 모든 파일 접근 권한 → 시작(온보딩) 화면 */
    data object GrantPermission : HomePrimary

    /** 카드 폴더 승인(SAF) */
    data class GrantFolderAccess(val volumeId: String) : HomePrimary

    /** 가져오기 화면으로. [newFiles]가 null이면 아직 확인 중 */
    data class Import(val newFiles: Int?, val newBytes: Long?) : HomePrimary

    /** 방금 끝난 가져오기를 닫고 검토로 */
    data class StartReview(val tab: ReviewTab) : HomePrimary

    data class ContinueReview(val tab: ReviewTab) : HomePrimary

    data object None : HomePrimary

    companion object {
        fun of(status: HomeCardStatus, counts: ReviewCounts?): HomePrimary {
            val reviewTab = counts?.let(::firstReviewTab)
            return when (status) {
                HomeCardStatus.NoPermission -> GrantPermission
                is HomeCardStatus.NeedsAccess -> GrantFolderAccess(status.volumeId)
                is HomeCardStatus.Checking -> Import(null, null)
                is HomeCardStatus.CardReady -> when {
                    status.summary.newFileCount > 0 -> Import(status.summary.newFileCount, status.summary.newBytes)
                    reviewTab != null -> ContinueReview(reviewTab)
                    else -> None
                }
                is HomeCardStatus.Finished -> reviewTab?.let(::StartReview) ?: None
                else -> reviewTab?.let(::ContinueReview) ?: None
            }
        }

        /** 검토할 것이 있는 첫 탭(흐림 → 연사 → 영상) */
        fun firstReviewTab(counts: ReviewCounts): ReviewTab? = when {
            counts.blur > 0 -> ReviewTab.BLUR
            counts.groups > 0 -> ReviewTab.BURST
            counts.videos > 0 -> ReviewTab.VIDEO
            else -> null
        }
    }
}
