package com.leepy0.photocull.data.review

import com.leepy0.photocull.analysis.BlurThresholds
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.db.MediaGroupEntity
import com.leepy0.photocull.data.db.ReviewRow
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * 검토·휴지통 화면이 읽는 데이터. 파일 이동은 하지 않는다(TrashManager 담당).
 *
 * 목록 변환(행 → 카드, 그룹 묶기, 정렬)은 [Dispatchers.Default]에서 한다: media_item에 쓰기가 있을 때마다(가져오는 중에는 파일마다)
 * 다시 계산되기 때문에 메인 스레드에서 하면 화면이 끊긴다. 결과가 같으면 내보내지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val mediaDao get() = db.mediaDao()
    private val groupDao get() = db.groupDao()

    private val threshold: Flow<Double> =
        settings.settings.map { BlurThresholds.threshold(it.blurSensitivity) }.distinctUntilChanged()

    private val shortVideoMs: Flow<Long> = settings.settings.map { it.shortVideoMs }.distinctUntilChanged()

    fun blurCandidates(): Flow<List<MediaCard>> =
        threshold.flatMapLatest { t -> mediaDao.observeBlurCandidates(t) }.toCards()

    fun blurCandidates(threshold: Double): Flow<List<MediaCard>> = mediaDao.observeBlurCandidates(threshold).toCards()

    /** 아직 판단이 남은 그룹(대기 중인 사진이 있고 2장 이상) */
    fun groups(): Flow<List<GroupCard>> =
        combine(groupDao.observeOpen(), mediaDao.observeGroupedItems(), threshold) { groups, rows, t ->
            buildGroups(groups, rows, t)
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    fun group(groupId: Long): Flow<GroupCard?> =
        groups().map { list -> list.firstOrNull { it.id == groupId } }.distinctUntilChanged()

    /** 짧은 영상(실수 촬영 후보) 먼저, 나머지는 용량 큰 순 */
    fun videos(): Flow<List<MediaCard>> =
        combine(mediaDao.observePendingVideos(), shortVideoMs) { rows, shortMs ->
            val cards = rows.map { it.toCard() }
            val (short, rest) = cards.partition { isShortVideo(it, shortMs) }
            short.sortedBy { it.durationMs ?: Long.MAX_VALUE } + rest
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    fun trashed(): Flow<List<MediaCard>> = mediaDao.observeTrashed().toCards()

    /**
     * 검토 대기 건수(탭 배지·알림용). 목록을 만들지 않고 COUNT 질의만 쓴다. 같은 Flow 인스턴스를 돌려주므로
     * 화면이 매번 다시 불러도 구독이 새로 시작되지 않는다. 구독할 때마다 DB에서 바로 세므로 first()도 최신 값이다.
     * 규칙은 [blurCandidates]·[groups]·[videos]와 같다(테스트로 확인).
     */
    fun counts(): Flow<ReviewCounts> = countsFlow

    private val countsFlow: Flow<ReviewCounts> =
        combine(
            threshold.flatMapLatest { t -> mediaDao.observeBlurCount(t) },
            mediaDao.observeGroupCounts(),
            shortVideoMs.flatMapLatest { ms -> mediaDao.observeVideoCounts(ms) },
        ) { blur, groups, videos ->
            ReviewCounts(
                blur = blur,
                groups = groups.groupCount,
                groupPhotos = groups.photoCount,
                videos = videos.total,
                shortVideos = videos.shortCount,
            )
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    private fun Flow<List<ReviewRow>>.toCards(): Flow<List<MediaCard>> =
        map { rows -> rows.map { it.toCard() } }.distinctUntilChanged().flowOn(Dispatchers.Default)

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

    /** [itemId]를 베스트로 고정. 그 사이 다시 묶여 그 그룹 멤버가 아니면 아무것도 하지 않는다. */
    suspend fun pinBest(groupId: Long, itemId: Long) {
        groupDao.pinBest(groupId, itemId)
    }

    companion object {
        fun isShortVideo(card: MediaCard, shortMs: Long): Boolean = card.durationMs != null && card.durationMs <= shortMs

        /**
         * 그룹 카드 만들기. 상대 흐림·전체 흐림은 베스트가 아니라 **그룹 최고 점수** 기준(DESIGN §4.3):
         * 사용자가 흐린 사진을 베스트로 고정해도 다른 장이 흐림으로 잘못 표시되거나 '전체가 흐림'이 꺼지지 않게.
         * 고정한 베스트가 최고 점수의 60% 미만이면 그 사진에도 흐림 배지가 붙는다(사실이므로).
         */
        internal fun buildGroups(groups: List<MediaGroupEntity>, rows: List<ReviewRow>, threshold: Double): List<GroupCard> {
            val byGroup = rows.groupBy { it.item.groupId }
            return groups.mapNotNull { g ->
                val members = byGroup[g.id].orEmpty().map { it.toCard() }
                if (members.size < 2 || members.none { it.reviewState == ReviewState.PENDING }) return@mapNotNull null
                val best = members.firstOrNull { it.id == g.bestItemId }
                    ?: members.maxByOrNull { it.sharpness ?: -1.0 }!!
                val maxScore = members.mapNotNull { it.sharpness }.maxOrNull()
                val relative = if (maxScore == null) emptySet() else members
                    .filter { it.sharpness != null && it.sharpness < maxScore * BlurThresholds.GROUP_RELATIVE_RATIO }
                    .mapTo(LinkedHashSet()) { it.id }
                GroupCard(
                    id = g.id,
                    kind = g.kind,
                    members = members,
                    bestId = best.id,
                    bestPinned = g.bestPinned && best.id == g.bestItemId,
                    relativeBlurIds = relative,
                    allBlurry = maxScore != null && maxScore < threshold,
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
