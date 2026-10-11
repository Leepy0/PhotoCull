package com.leepy0.photocull.ui.trash

import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.data.settings.AppSettings
import com.leepy0.photocull.trash.RestoreReport

enum class TrashPhase { LOADING, ERROR, READY }

/**
 * 영구 삭제 확인 대화상자. [ids]는 대화상자를 띄울 때 화면에 보이던 항목(선택 또는 휴지통 전체)이고,
 * 확인하면 이 목록만 지운다. 그사이 휴지통에 새로 들어온 것은 지우지 않는다.
 */
data class DeleteConfirm(
    val ids: List<Long>,
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

    /** 남은 보관 일수(올림). 24시간이 안 남았으면 0("곧 영구 삭제"). 무기한이거나 기록이 없으면 null */
    fun daysLeft(card: MediaCard): Int? = card.trashedAt?.let { TrashRetention.daysLeft(it, retentionDays, now) }
}

/** 휴지통 보관 기간 계산. 영구 삭제 기준(TrashManager.purgeExpired)은 휴지통에 들어간 지 [retentionDays]일이 지난 때 */
object TrashRetention {
    private const val DAY_MS = 86_400_000L

    /**
     * 남은 일수. 하루 이상 남았으면 올림("1일 4시간" → 2), 24시간이 안 남았거나 이미 지났으면 0.
     * 그래서 한 시간 남은 항목이 "1일 남음"으로 보이지 않는다. [retentionDays] = 0(무기한)이면 null
     */
    fun daysLeft(trashedAt: Long, retentionDays: Int, now: Long): Int? {
        if (retentionDays <= 0) return null
        val remaining = trashedAt + retentionDays * DAY_MS - now
        if (remaining < DAY_MS) return 0
        return ((remaining + DAY_MS - 1) / DAY_MS).toInt()
    }
}

/** 스낵바로 알릴 결과 */
sealed interface TrashMessage {
    /** [count] = 복원함, [failed] = 복원하지 못함, [gone] = 휴지통 파일이 이미 없어 목록에서만 뺌 */
    data class Restored(val count: Int, val failed: Int, val gone: Int = 0) : TrashMessage
    data object RestoreFailed : TrashMessage
    data class Deleted(val count: Int, val failed: Int, val bytes: Long) : TrashMessage
    data object DeleteFailed : TrashMessage

    companion object {
        fun restored(requested: Int, report: RestoreReport): TrashMessage {
            if (report.restored <= 0 && report.gone <= 0) return RestoreFailed
            val failed = (requested - report.restored - report.gone).coerceAtLeast(0)
            return Restored(report.restored, failed, report.gone)
        }

        fun deleted(requested: Int, deleted: Int, bytes: Long): TrashMessage =
            if (deleted <= 0 && requested > 0) DeleteFailed else Deleted(deleted, (requested - deleted).coerceAtLeast(0), bytes)
    }
}

/** 남은 일수가 이 값 이하이면 경고 표시(아이콘 + 글자) */
internal const val TRASH_WARN_DAYS = 3
