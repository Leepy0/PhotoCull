package com.leepy0.photocull.ui.review

import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.data.db.GroupKind
import com.leepy0.photocull.data.db.MediaType
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.review.GroupCard
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.data.settings.AppSettings
import com.leepy0.photocull.data.settings.BlurSensitivity
import com.leepy0.photocull.trash.TrashBatch
import com.leepy0.photocull.trash.TrashResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/** 2026-10-11 14:03:12 KST 근처 */
const val BASE_TIME = 1_791_694_992_000L
const val MB = 1024L * 1024L

fun photo(
    id: Long,
    sharpness: Double? = 40.0,
    bytes: Long = 12 * MB,
    hasRaw: Boolean = false,
    capturedAt: Long = BASE_TIME + id * 300,
    groupId: Long? = null,
    state: ReviewState = ReviewState.PENDING,
    focusX: Float? = 0.4f,
    focusY: Float? = 0.55f,
) = MediaCard(
    id = id,
    path = "/storage/emulated/0/DCIM/R10/IMG_${1000 + id}.JPG",
    fileName = "IMG_${1000 + id}.JPG",
    type = MediaType.PHOTO,
    mimeType = "image/jpeg",
    capturedAt = capturedAt,
    bytes = bytes,
    hasRaw = hasRaw,
    width = 6000,
    height = 4000,
    sharpness = sharpness,
    focusX = focusX,
    focusY = focusY,
    durationMs = null,
    reviewState = state,
    groupId = groupId,
    trashedAt = null,
    iso = 800,
    exposure = "0.004",
    aperture = "5.6",
    focalLength = "135",
)

fun video(id: Long, durationMs: Long, bytes: Long) = MediaCard(
    id = id,
    path = "/storage/emulated/0/DCIM/R10/MVI_${2000 + id}.MP4",
    fileName = "MVI_${2000 + id}.MP4",
    type = MediaType.VIDEO,
    mimeType = "video/mp4",
    capturedAt = BASE_TIME + id * 1000,
    bytes = bytes,
    hasRaw = false,
    width = 3840,
    height = 2160,
    sharpness = null,
    focusX = null,
    focusY = null,
    durationMs = durationMs,
    reviewState = ReviewState.PENDING,
    groupId = null,
    trashedAt = null,
    iso = null,
    exposure = null,
    aperture = null,
    focalLength = null,
)

fun group(
    id: Long,
    memberIds: List<Long>,
    bestId: Long = memberIds[memberIds.size / 2],
    kind: GroupKind = GroupKind.BURST,
    allBlurry: Boolean = false,
    keptIds: Set<Long> = emptySet(),
    blurIds: Set<Long> = emptySet(),
): GroupCard {
    val members = memberIds.map { mid ->
        photo(
            id = mid,
            sharpness = if (mid == bestId) 180.0 else if (mid in blurIds) 60.0 else 140.0 - (mid % 7),
            groupId = id,
            state = if (mid in keptIds) ReviewState.KEPT else ReviewState.PENDING,
            hasRaw = true,
            bytes = 30 * MB,
        )
    }
    return GroupCard(
        id = id,
        kind = kind,
        members = members,
        bestId = bestId,
        bestPinned = false,
        relativeBlurIds = blurIds,
        allBlurry = allBlurry,
    )
}

object FakeData {
    val blur: List<MediaCard> = listOf(
        photo(1, 12.0, hasRaw = true, bytes = 33 * MB),
        photo(2, 18.0),
        photo(3, 25.0, hasRaw = true, bytes = 34 * MB),
        photo(4, 31.0),
        photo(5, 44.0),
        photo(6, 52.0),
        photo(7, 58.0, hasRaw = true, bytes = 31 * MB),
        photo(8, 63.0),
        photo(9, 71.0),
        photo(10, 77.0),
    )

    val groups: List<GroupCard> = listOf(
        group(100, (101L..112L).toList(), bestId = 105, blurIds = setOf(101, 102, 111)),
        group(200, (201L..203L).toList(), bestId = 202, kind = GroupKind.SIMILAR),
        group(300, (301L..306L).toList(), bestId = 303, allBlurry = true),
    )

    val videos: List<MediaCard> = listOf(
        video(51, 1_200, 8 * MB),
        video(52, 2_400, 15 * MB),
        video(53, 3_000, 19 * MB),
        video(54, 95_000, 640 * MB),
        video(55, 42_000, 280 * MB),
    )
}

/** 메모리에서 동작하는 가짜 데이터 소스. 휴지통·남기기는 목록에서 빼고, 실행 취소는 되돌린다. */
class FakeReviewDataSource(
    blur: List<MediaCard> = FakeData.blur,
    groups: List<GroupCard> = FakeData.groups,
    videos: List<MediaCard> = FakeData.videos,
    override val workScope: CoroutineScope,
) : ReviewDataSource {
    val blurFlow = MutableStateFlow(blur)
    val groupsFlow = MutableStateFlow(groups)
    val videosFlow = MutableStateFlow(videos)
    val settingsFlow = MutableStateFlow(AppSettings())
    val analysisFlow = MutableStateFlow(AnalysisStatus())

    private val removed = mutableMapOf<Long, MediaCard>()
    val trashCalls = mutableListOf<Pair<List<Long>, List<Long>>>()
    val keepCalls = mutableListOf<List<Long>>()
    val unkeepCalls = mutableListOf<List<Long>>()
    val undoCalls = mutableListOf<TrashBatch>()
    val pinCalls = mutableListOf<Pair<Long, Long>>()
    var regroupCalls = 0
    var failTrash = false
    var failedPerCall = 0

    /** false면 실행 취소가 목록을 바로 갱신하지 않는다(실제 DB처럼 늦게 오는 경우) */
    var publishOnUndo = true

    /** 정해 두면 휴지통 이동이 이것이 끝날 때까지 기다린다(작업 중 상태를 시험) */
    var trashGate: CompletableDeferred<Unit>? = null

    override fun blurCandidates(): Flow<List<MediaCard>> = blurFlow
    override fun groups(): Flow<List<GroupCard>> = groupsFlow
    override fun group(groupId: Long): Flow<GroupCard?> = groupsFlow.map { list -> list.firstOrNull { it.id == groupId } }
    override fun videos(): Flow<List<MediaCard>> = videosFlow
    override fun settings(): Flow<AppSettings> = settingsFlow
    override val analysis: StateFlow<AnalysisStatus> get() = analysisFlow

    override suspend fun setBlurSensitivity(value: BlurSensitivity) {
        settingsFlow.value = settingsFlow.value.copy(blurSensitivity = value)
    }

    override suspend fun keep(ids: Collection<Long>) {
        keepCalls += ids.toList()
        removeEverywhere(ids.toSet(), groupPendingToKept = true)
    }

    override suspend fun unkeep(ids: Collection<Long>) {
        unkeepCalls += ids.toList()
        restore(ids.toSet())
    }

    override suspend fun moveToTrash(ids: Collection<Long>, keepIds: Collection<Long>): TrashResult {
        trashGate?.await()
        if (failTrash) error("disk full")
        trashCalls += ids.toList() to keepIds.toList()
        val failed = failedPerCall.coerceAtMost(ids.size)
        val moved = ids.toList().dropLast(failed)
        removeEverywhere(moved.toSet() + keepIds.toSet(), groupPendingToKept = true)
        val bytes = moved.sumOf { removed[it]?.bytes ?: 0L }
        return TrashResult(TrashBatch(moved, keepIds.toList()), moved.size, failed, bytes)
    }

    override suspend fun undoTrash(batch: TrashBatch) {
        undoCalls += batch
        if (publishOnUndo) restore((batch.trashedIds + batch.keptIds).toSet()) else gone -= (batch.trashedIds + batch.keptIds).toSet()
    }

    override fun regroup() {
        regroupCalls++
    }

    override suspend fun pinBest(groupId: Long, itemId: Long) {
        pinCalls += groupId to itemId
        groupsFlow.value = groupsFlow.value.map { g -> if (g.id == groupId) g.copy(bestId = itemId, bestPinned = true) else g }
    }

    // ---- 내부: 원래 목록을 기억했다가 되돌린다 ----
    private val originalBlur = blur
    private val originalVideos = videos
    private val originalGroups = groups
    private val gone = mutableSetOf<Long>()

    private fun removeEverywhere(ids: Set<Long>, groupPendingToKept: Boolean) {
        (originalBlur + originalVideos + originalGroups.flatMap { it.members }).filter { it.id in ids }.forEach { removed[it.id] = it }
        gone += ids
        publish()
    }

    private fun restore(ids: Set<Long>) {
        gone -= ids
        publish()
    }

    private fun publish() {
        blurFlow.value = originalBlur.filter { it.id !in gone }
        videosFlow.value = originalVideos.filter { it.id !in gone }
        groupsFlow.value = originalGroups.mapNotNull { g ->
            val members = g.members.map { m -> if (m.id in gone) m.copy(reviewState = ReviewState.KEPT) else m }
            // 판단 전인 사진이 없으면 그룹은 목록에서 빠진다(저장소와 같은 규칙)
            if (members.none { it.reviewState == ReviewState.PENDING }) null else g.copy(members = members)
        }
    }
}
