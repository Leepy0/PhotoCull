package com.leepy0.photocull.ui.review

import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.review.GroupCard
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.data.review.ReviewRepository
import com.leepy0.photocull.data.settings.AppSettings
import com.leepy0.photocull.data.settings.BlurSensitivity
import com.leepy0.photocull.trash.TrashBatch
import com.leepy0.photocull.trash.TrashResult
import com.leepy0.photocull.ui.nav.ReviewTab

/** 수량 단위: 사진은 "장", 영상은 "개" */
enum class CountUnit { PHOTO, VIDEO }

/** 실행 취소 후 다시 고를 선택(삭제 직전 선택을 되살린다) */
data class SelectionRestore(val tab: ReviewTab, val ids: Set<Long>)

/** 스낵바로 알릴 결과. 되돌릴 수 있는 작업은 [undoable]. */
sealed interface ReviewMessage {
    val undoable: Boolean get() = false

    /**
     * 휴지통으로 옮김(일부 실패 포함). [batch]로 실행 취소.
     * [regroupOnUndo]: 그룹을 다시 묶은 작업(모든 그룹 일괄 정리)이라 되돌린 뒤에도 다시 묶어야 한다.
     */
    data class Trashed(
        val moved: Int,
        val failed: Int,
        val unit: CountUnit,
        val batch: TrashBatch,
        val restore: SelectionRestore? = null,
        val regroupOnUndo: Boolean = false,
    ) : ReviewMessage {
        override val undoable: Boolean get() = batch.trashedIds.isNotEmpty() || batch.keptIds.isNotEmpty()
    }

    /** 하나도 옮기지 못함 */
    data object TrashFailed : ReviewMessage

    /** 아직 가져오는 중이라 옮기지 않음 */
    data object StillImporting : ReviewMessage

    /** 폰에서 이미 지워져 있어 목록에서만 뺌(실행 취소 없음) */
    data class AlreadyGone(val count: Int) : ReviewMessage

    /** '남기기'로 표시. [ids]를 다시 검토 대기로 돌려 실행 취소 */
    data class Kept(
        val ids: List<Long>,
        val unit: CountUnit,
        val restore: SelectionRestore? = null,
    ) : ReviewMessage {
        override val undoable: Boolean get() = ids.isNotEmpty()
    }

    data object ActionFailed : ReviewMessage
    data object UndoFailed : ReviewMessage

    companion object {
        /**
         * 휴지통 결과를 메시지로. 실제로 옮긴 수([TrashResult.movedCount])만 믿는다.
         * 하나도 못 옮겼으면 실패(실행 취소 없음)로 알린다.
         */
        fun fromTrash(
            result: TrashResult,
            unit: CountUnit,
            restore: SelectionRestore?,
            regroupOnUndo: Boolean = false,
        ): ReviewMessage =
            if (result.movedCount <= 0) {
                when {
                    result.importingCount > 0 && result.importingCount >= result.failedCount -> StillImporting
                    result.failedCount == 0 && result.goneCount > 0 -> AlreadyGone(result.goneCount)
                    else -> TrashFailed
                }
            } else {
                Trashed(
                    moved = result.movedCount,
                    failed = result.failedCount,
                    unit = unit,
                    batch = result.batch,
                    restore = restore,
                    regroupOnUndo = regroupOnUndo,
                )
            }
    }
}

/**
 * 모든 그룹에서 베스트만 남기기: 한 번의 휴지통 이동으로 처리해 실행 취소도 한 번에.
 * 전체가 흐린 그룹([GroupCard.allBlurry])은 베스트도 흐리므로 '남기기'로 확정하지 않고 검토 대기로 둔다
 * (그룹을 다시 묶으면 혼자 남은 베스트가 흐림 탭에 다시 나온다). [blurryGroupCount]는 그런 그룹 수.
 */
data class BurstPlan(
    val deleteIds: List<Long>,
    val keepIds: List<Long>,
    val deleteBytes: Long,
    val blurryGroupCount: Int = 0,
) {
    val isEmpty: Boolean get() = deleteIds.isEmpty()

    companion object {
        fun of(groups: List<GroupCard>): BurstPlan {
            val delete = LinkedHashSet<Long>()
            val keep = LinkedHashSet<Long>()
            var bytes = 0L
            var blurry = 0
            for (g in groups) {
                val selection = g.defaultSelection()
                if (g.allBlurry) blurry++
                for (m in g.members) {
                    if (m.id in selection) {
                        if (delete.add(m.id)) bytes += m.bytes
                    } else if (m.reviewState == ReviewState.PENDING && !g.allBlurry) {
                        keep.add(m.id)
                    }
                }
            }
            keep.removeAll(delete)
            return BurstPlan(delete.toList(), keep.toList(), bytes, blurry)
        }
    }
}

enum class LoadPhase { LOADING, ERROR, READY }

data class ReviewUiState(
    val phase: LoadPhase = LoadPhase.LOADING,
    val tab: ReviewTab = ReviewTab.BLUR,
    val blur: List<MediaCard> = emptyList(),
    val groups: List<GroupCard> = emptyList(),
    val videos: List<MediaCard> = emptyList(),
    val sensitivity: BlurSensitivity = BlurSensitivity.NORMAL,
    val shortVideoMs: Long = AppSettings().shortVideoMs,
    val blurSelection: Set<Long> = emptySet(),
    val videoSelection: Set<Long> = emptySet(),
    val analysis: AnalysisStatus = AnalysisStatus(),
    val busy: Boolean = false,
    /** 이번에 이미 삭제·유지를 한 탭("남은 n장"이라고 부를 수 있는 탭) */
    val processedTabs: Set<ReviewTab> = emptySet(),
) {
    val shortVideos: List<MediaCard> get() = videos.filter { ReviewRepository.isShortVideo(it, shortVideoMs) }
    val otherVideos: List<MediaCard> get() = videos.filterNot { ReviewRepository.isShortVideo(it, shortVideoMs) }

    /** 그룹 안에서 아직 판단 전인 사진 수 */
    val burstPendingCount: Int get() = groups.sumOf { g -> g.members.count { it.reviewState == ReviewState.PENDING } }

    val burstPlan: BurstPlan get() = BurstPlan.of(groups)

    val allDone: Boolean get() = blur.isEmpty() && groups.isEmpty() && videos.isEmpty()

    fun count(tab: ReviewTab): Int = when (tab) {
        ReviewTab.BLUR -> blur.size
        ReviewTab.BURST -> groups.size
        ReviewTab.VIDEO -> videos.size
    }

    fun items(tab: ReviewTab): List<MediaCard> = when (tab) {
        ReviewTab.BLUR -> blur
        ReviewTab.BURST -> emptyList()
        ReviewTab.VIDEO -> videos
    }

    fun selection(tab: ReviewTab): Set<Long> = when (tab) {
        ReviewTab.BLUR -> blurSelection
        ReviewTab.BURST -> emptySet()
        ReviewTab.VIDEO -> videoSelection
    }

    fun selectedBytes(tab: ReviewTab): Long {
        val sel = selection(tab)
        if (sel.isEmpty()) return 0L
        return items(tab).sumOf { if (it.id in sel) it.bytes else 0L }
    }

    /** 'n장 모두 유지'의 대상 수 */
    fun remainingCount(tab: ReviewTab): Int = when (tab) {
        ReviewTab.BURST -> burstPendingCount
        else -> items(tab).size
    }

    fun unit(tab: ReviewTab): CountUnit = if (tab == ReviewTab.VIDEO) CountUnit.VIDEO else CountUnit.PHOTO

    /** 탭 목록이 비어 있는지(빈 화면이 분석 진행을 대신 보여준다) */
    fun isTabEmpty(tab: ReviewTab): Boolean = count(tab) == 0

    /** 항목이 남아 있는 다음 탭(현재 탭 다음부터 차례로). 없으면 null */
    fun nextTabWithItems(from: ReviewTab): ReviewTab? {
        val order = ReviewTab.entries
        val start = order.indexOf(from)
        return (1 until order.size).map { order[(start + it) % order.size] }.firstOrNull { count(it) > 0 }
    }
}

/** 그룹 화면 상태 */
sealed interface GroupUiState {
    data object Loading : GroupUiState

    /** 그룹이 사라짐(다른 곳에서 정리됐거나 다시 묶임) */
    data object Missing : GroupUiState

    data object Error : GroupUiState

    data class Content(
        val group: GroupCard,
        val selection: Set<Long>,
        val currentId: Long,
        val busy: Boolean = false,
    ) : GroupUiState {
        val current: MediaCard get() = group.members.firstOrNull { it.id == currentId } ?: group.best
        val currentIndex: Int get() = group.members.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        val selectedBytes: Long get() = group.members.sumOf { if (it.id in selection) it.bytes else 0L }
        val selectedCount: Int get() = group.members.count { it.id in selection }

        /** 삭제하지 않고 '남기기'로 처리될 사진(아직 판단 전인 것만) */
        val keepIds: List<Long> get() = group.members.filter { it.id !in selection && it.reviewState == ReviewState.PENDING }.map { it.id }
        val pendingIds: List<Long> get() = group.members.filter { it.reviewState == ReviewState.PENDING }.map { it.id }
    }
}
