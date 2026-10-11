package com.leepy0.photocull.analysis

import com.leepy0.photocull.data.db.GroupKind
import com.leepy0.photocull.data.db.GroupingDiff
import com.leepy0.photocull.data.db.GroupingRow
import com.leepy0.photocull.data.db.MediaGroupEntity

/** 그룹 계산 입력 1장(대표 사진) */
data class GroupInput(
    val id: Long,
    val capturedAt: Long,
    /** 카드 상대 경로. 촬영 시각이 같을 때 파일 번호 순서로 정렬하는 데 쓴다. */
    val srcRelPath: String,
    val sharpness: Double?,
    val dhash: Long?,
)

fun GroupingRow.toGroupInput(): GroupInput = GroupInput(id, capturedAt, srcRelPath, sharpness, dhash)

/** 계산된 그룹 1개. [memberIds]는 촬영 순서. */
data class PlannedGroup(
    val kind: GroupKind,
    val memberIds: List<Long>,
    val bestId: Long,
    val bestPinned: Boolean,
    val startAt: Long,
    val endAt: Long,
)

/**
 * 연사/유사 그룹 계산(DESIGN.md §4.4). 순수 함수라 JVM 테스트로 검증한다.
 *
 * 1. 촬영 시각, 같으면 카드 상대 경로(파일 번호) 순으로 정렬
 * 2. 바로 앞 사진과의 간격 ≤ [burstGapMs]면 연사 연결.
 *    아니면 간격 ≤ [similarGapMs]이고 둘 다 dHash가 있고 해밍 거리 ≤ [maxDistance]면 유사 연결.
 * 3. 연결이 끊기는 곳에서 그룹을 나눈다. 연사 연결이 하나라도 있으면 BURST, 아니면 SIMILAR. 2장 이상만 남긴다.
 * 4. 베스트 = 선명도 최고(동점이면 촬영 순서상 앞의 것). 단, 사용자가 이전에 고정한 베스트([pinnedBestIds])가
 *    새 그룹에 있으면 그것을 유지한다(여러 개면 그중 선명도 최고).
 */
object Grouping {

    val ORDER: Comparator<GroupInput> = compareBy<GroupInput>({ it.capturedAt }, { it.srcRelPath }, { it.id })

    fun group(
        rows: List<GroupInput>,
        burstGapMs: Long,
        similarGapMs: Long,
        maxDistance: Int,
        pinnedBestIds: Set<Long> = emptySet(),
    ): List<PlannedGroup> {
        if (rows.size < 2) return emptyList()
        val sorted = rows.sortedWith(ORDER)
        val result = ArrayList<PlannedGroup>()

        var start = 0
        var burst = false
        for (i in 1..sorted.size) {
            val link = if (i < sorted.size) link(sorted[i - 1], sorted[i], burstGapMs, similarGapMs, maxDistance) else Link.NONE
            if (link == Link.NONE) {
                if (i - start >= 2) {
                    result += build(sorted.subList(start, i), if (burst) GroupKind.BURST else GroupKind.SIMILAR, pinnedBestIds)
                }
                start = i
                burst = false
            } else if (link == Link.BURST) {
                burst = true
            }
        }
        return result
    }

    private enum class Link { NONE, BURST, SIMILAR }

    private fun link(prev: GroupInput, cur: GroupInput, burstGapMs: Long, similarGapMs: Long, maxDistance: Int): Link {
        val gap = cur.capturedAt - prev.capturedAt // 정렬했으므로 ≥ 0
        if (gap <= burstGapMs) return Link.BURST
        if (gap <= similarGapMs && prev.dhash != null && cur.dhash != null &&
            DHash.hamming(prev.dhash, cur.dhash) <= maxDistance
        ) return Link.SIMILAR
        return Link.NONE
    }

    private fun build(members: List<GroupInput>, kind: GroupKind, pinnedBestIds: Set<Long>): PlannedGroup {
        val pinned = members.filter { it.id in pinnedBestIds }
        val best = sharpest(pinned) ?: sharpest(members)!!
        return PlannedGroup(
            kind = kind,
            memberIds = members.map { it.id },
            bestId = best.id,
            bestPinned = pinned.isNotEmpty(),
            startAt = members.first().capturedAt,
            endAt = members.last().capturedAt,
        )
    }

    /** 선명도 최고. 동점이면 앞(촬영 순서)의 것. 선명도가 없으면 가장 낮게 본다. */
    private fun sharpest(list: List<GroupInput>): GroupInput? {
        var best: GroupInput? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (m in list) {
            val s = m.sharpness ?: Double.NEGATIVE_INFINITY
            if (best == null || s > bestScore) {
                best = m
                bestScore = s
            }
        }
        return best
    }

    /**
     * 새 그룹마다 재사용할 기존 그룹 id를 정한다. 멤버가 가장 많이 겹치는 기존 그룹의 id를 물려받아,
     * 다시 묶어도 같은 그룹은 같은 id로 남는다(열려 있는 그룹 화면이 사라지지 않게).
     * 기존 id 하나는 한 번만 쓰고, 겹치는 그룹이 없으면 null(새 id 발급).
     *
     * @param previousGroupOf 사진 id → 현재 소속 그룹 id
     * @param existingGroupIds 실제로 존재하는 그룹 id(지워진 그룹을 가리키는 낡은 groupId는 무시)
     */
    fun assignIds(
        groups: List<PlannedGroup>,
        previousGroupOf: Map<Long, Long>,
        existingGroupIds: Set<Long>,
    ): List<Long?> {
        data class Candidate(val groupIndex: Int, val oldId: Long, val overlap: Int)
        val candidates = ArrayList<Candidate>()
        groups.forEachIndexed { index, g ->
            g.memberIds.mapNotNull { previousGroupOf[it] }
                .filter { it in existingGroupIds }
                .groupingBy { it }.eachCount()
                .forEach { (oldId, n) -> candidates += Candidate(index, oldId, n) }
        }
        // 많이 겹치는 순, 같으면 앞 그룹·작은 id 우선(결정적)
        candidates.sortWith(compareByDescending<Candidate> { it.overlap }.thenBy { it.groupIndex }.thenBy { it.oldId })
        val assigned = arrayOfNulls<Long>(groups.size)
        val used = HashSet<Long>()
        for (c in candidates) {
            if (assigned[c.groupIndex] != null || c.oldId in used) continue
            assigned[c.groupIndex] = c.oldId
            used += c.oldId
        }
        return assigned.toList()
    }

    /**
     * 계산 결과([groups] + [assignedIds]의 id)와 지금 DB 상태의 차이. 적용하면 결과가 [GroupingDao.replaceAll]과 같아진다:
     * 계획된 그룹만 남고, 그 멤버만 groupId를 가지며, 나머지 행(휴지통으로 간 사진 등)은 groupId가 비워진다.
     *
     * @param assignedIds [assignIds] 결과(기존 그룹 id 또는 null = 새 그룹)
     * @param existing 지금 있는 그룹
     * @param currentGroupOf 지금 groupId가 있는 모든 행: 사진 id → 그룹 id
     */
    fun diff(
        groups: List<PlannedGroup>,
        assignedIds: List<Long?>,
        existing: List<MediaGroupEntity>,
        currentGroupOf: Map<Long, Long>,
    ): GroupingDiff {
        require(groups.size == assignedIds.size)
        val existingById = existing.associateBy { it.id }
        val inserts = ArrayList<Pair<MediaGroupEntity, List<Long>>>()
        val updates = ArrayList<MediaGroupEntity>()
        val moves = LinkedHashMap<Long, List<Long>>()
        val keptGroupIds = HashSet<Long>()
        val plannedMembers = HashSet<Long>()
        groups.forEachIndexed { i, g ->
            plannedMembers += g.memberIds
            val id = assignedIds[i]?.takeIf { it in existingById }
            val entity = MediaGroupEntity(
                id = id ?: 0,
                kind = g.kind,
                bestItemId = g.bestId,
                bestPinned = g.bestPinned,
                count = g.memberIds.size,
                startAt = g.startAt,
                endAt = g.endAt,
            )
            if (id == null) {
                inserts += entity to g.memberIds
            } else {
                keptGroupIds += id
                if (existingById[id] != entity) updates += entity
                val moving = g.memberIds.filter { currentGroupOf[it] != id }
                if (moving.isNotEmpty()) moves[id] = moving
            }
        }
        return GroupingDiff(
            inserts = inserts,
            updates = updates,
            deleteGroupIds = existing.map { it.id }.filter { it !in keptGroupIds },
            moves = moves,
            clearItemIds = currentGroupOf.keys.filter { it !in plannedMembers }.sorted(),
        )
    }
}
