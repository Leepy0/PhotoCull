package com.leepy0.photocull.data.review

import com.leepy0.photocull.analysis.BlurThresholds
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.db.MediaGroupEntity
import com.leepy0.photocull.data.db.ReviewRow
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** 검토·휴지통 화면이 읽는 데이터. 파일 이동은 하지 않는다(TrashManager 담당). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val mediaDao get() = db.mediaDao()
    private val groupDao get() = db.groupDao()

    private val threshold: Flow<Double> =
        settings.settings.map { BlurThresholds.threshold(it.blurSensitivity) }.distinctUntilChanged()

    fun blurCandidates(): Flow<List<MediaCard>> =
        threshold.flatMapLatest { t -> mediaDao.observeBlurCandidates(t) }.map { rows -> rows.map { it.toCard() } }

    fun blurCandidates(threshold: Double): Flow<List<MediaCard>> =
        mediaDao.observeBlurCandidates(threshold).map { rows -> rows.map { it.toCard() } }

    /** 아직 판단이 남은 그룹(대기 중인 사진이 있고 2장 이상) */
    fun groups(): Flow<List<GroupCard>> =
        combine(groupDao.observeAll(), mediaDao.observeGroupedItems(), threshold) { groups, rows, t ->
            buildGroups(groups, rows, t)
        }

    fun group(groupId: Long): Flow<GroupCard?> = groups().map { list -> list.firstOrNull { it.id == groupId } }

    /** 짧은 영상(실수 촬영 후보) 먼저, 나머지는 용량 큰 순 */
    fun videos(): Flow<List<MediaCard>> =
        combine(mediaDao.observePendingVideos(), settings.settings.map { it.shortVideoMs }.distinctUntilChanged()) { rows, shortMs ->
            val cards = rows.map { it.toCard() }
            val (short, rest) = cards.partition { isShortVideo(it, shortMs) }
            short.sortedBy { it.durationMs ?: Long.MAX_VALUE } + rest
        }

    fun trashed(): Flow<List<MediaCard>> = mediaDao.observeTrashed().map { rows -> rows.map { it.toCard() } }

    fun counts(): Flow<ReviewCounts> =
        combine(
            blurCandidates(),
            groups(),
            videos(),
            settings.settings.map { it.shortVideoMs }.distinctUntilChanged(),
        ) { blur, groups, videos, shortMs ->
            ReviewCounts(
                blur = blur.size,
                groups = groups.size,
                groupPhotos = groups.sumOf { it.members.size },
                videos = videos.size,
                shortVideos = videos.count { isShortVideo(it, shortMs) },
            )
        }

    suspend fun items(ids: List<Long>): List<MediaCard> {
        if (ids.isEmpty()) return emptyList()
        val byId = ids.chunked(500).flatMap { mediaDao.reviewRows(it) }.associateBy { it.item.id }
        return ids.mapNotNull { byId[it]?.toCard() }
    }

    /** 검토 완료로 남기기(같은 촬영 단위 포함) */
    suspend fun keep(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        ids.toList().chunked(500).forEach { mediaDao.setUnitReviewState(it, ReviewState.KEPT) }
    }

    /** '남기기'를 되돌려 다시 검토 대기로 */
    suspend fun unkeep(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        ids.toList().chunked(500).forEach { mediaDao.setUnitReviewState(it, ReviewState.PENDING) }
    }

    suspend fun pinBest(groupId: Long, itemId: Long) = groupDao.pinBest(groupId, itemId)

    companion object {
        fun isShortVideo(card: MediaCard, shortMs: Long): Boolean = card.durationMs != null && card.durationMs <= shortMs

        internal fun buildGroups(groups: List<MediaGroupEntity>, rows: List<ReviewRow>, threshold: Double): List<GroupCard> {
            val byGroup = rows.groupBy { it.item.groupId }
            return groups.mapNotNull { g ->
                val members = byGroup[g.id].orEmpty().map { it.toCard() }
                if (members.size < 2 || members.none { it.reviewState == ReviewState.PENDING }) return@mapNotNull null
                val best = members.firstOrNull { it.id == g.bestItemId }
                    ?: members.maxByOrNull { it.sharpness ?: -1.0 }!!
                val bestScore = best.sharpness
                val relative = if (bestScore == null) emptySet() else members
                    .filter { it.id != best.id && it.sharpness != null && it.sharpness < bestScore * BlurThresholds.GROUP_RELATIVE_RATIO }
                    .map { it.id }.toSet()
                GroupCard(
                    id = g.id,
                    kind = g.kind,
                    members = members,
                    bestId = best.id,
                    bestPinned = g.bestPinned && best.id == g.bestItemId,
                    relativeBlurIds = relative,
                    allBlurry = bestScore != null && bestScore < threshold,
                )
            }.sortedBy { it.startAt }
        }
    }
}

internal fun ReviewRow.toCard(): MediaCard {
    val m = item
    return MediaCard(
        id = m.id,
        path = (if (m.reviewState == ReviewState.TRASHED) m.trashPath else null) ?: m.dstPath ?: m.srcLocation,
        fileName = m.fileName,
        type = m.type,
        mimeType = m.mimeType,
        capturedAt = m.capturedAt,
        bytes = unitBytes,
        hasRaw = rawCount > 0,
        width = m.width,
        height = m.height,
        sharpness = m.sharpness,
        focusX = m.focusX,
        focusY = m.focusY,
        durationMs = m.durationMs,
        reviewState = m.reviewState,
        groupId = m.groupId,
        trashedAt = m.trashedAt,
        iso = m.iso,
        exposure = m.exposure,
        aperture = m.aperture,
        focalLength = m.focalLength,
    )
}
