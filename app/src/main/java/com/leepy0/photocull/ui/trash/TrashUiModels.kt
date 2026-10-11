package com.leepy0.photocull.ui.trash

import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.data.settings.AppSettings
import com.leepy0.photocull.util.Format

enum class TrashPhase { LOADING, ERROR, READY }

/** 영구 삭제 확인 대화상자. [ids] = null이면 휴지통 비우기 */
data class DeleteConfirm(
    val ids: List<Long>?,
    val count: Int,
    val bytes: Long,
    val hasRaw: Boolean,
)

data class TrashUiState(
    val phase: TrashPhase = TrashPhase.LOADING,
    /** 최근에 지운 순 */
    val items: List<MediaCard> = emptyList(),
    val selection: Set<Long> = emptySet(),
    /** 0 = 무기한 */
    val retentionDays: Int = AppSettings().trashRetentionDays,
    /** 남은 일수 계산 기준 시각 */
    val now: Long = 0,
    val busy: Boolean = false,
    val confirm: DeleteConfirm? = null,
) {
    val isEmpty: Boolean get() = items.isEmpty()
    val totalBytes: Long get() = items.sumOf { it.bytes }
    val selectedItems: List<MediaCard> get() = items.filter { it.id in selection }
    val selectedBytes: Long get() = selectedItems.sumOf { it.bytes }
    val allSelected: Boolean get() = items.isNotEmpty() && selection.size == items.size

    /** 남은 보관 일수. 무기한이거나 기록이 없으면 null */
    fun daysLeft(card: MediaCard): Int? = card.trashedAt?.let { Format.daysLeft(it, retentionDays, now) }
}

/** 스낵바로 알릴 결과 */
sealed interface TrashMessage {
    data class Restored(val count: Int, val failed: Int) : TrashMessage
    data object RestoreFailed : TrashMessage
    data class Deleted(val count: Int, val failed: Int, val bytes: Long) : TrashMessage
    data object DeleteFailed : TrashMessage

    companion object {
        fun restored(requested: Int, restored: Int): TrashMessage =
            if (restored <= 0) RestoreFailed else Restored(restored, (requested - restored).coerceAtLeast(0))

        fun deleted(requested: Int, deleted: Int, bytes: Long): TrashMessage =
            if (deleted <= 0 && requested > 0) DeleteFailed else Deleted(deleted, (requested - deleted).coerceAtLeast(0), bytes)
    }
}

/** 남은 일수가 이 값 이하이면 경고 표시(아이콘 + 글자) */
internal const val TRASH_WARN_DAYS = 3
