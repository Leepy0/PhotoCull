package com.leepy0.photocull.analysis

import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.data.db.GroupKind
import org.junit.Test

class GroupingTest {

    private val burst = 1_000L
    private val similar = 10_000L
    private val maxDist = 10

    private fun row(id: Long, t: Long, sharp: Double? = 100.0, dhash: Long? = null, path: String = "DCIM/100CANON/IMG_%04d.JPG".format(id)) =
        GroupInput(id, t, path, sharp, dhash)

    private fun group(rows: List<GroupInput>, pinned: Set<Long> = emptySet()) =
        Grouping.group(rows, burst, similar, maxDist, pinned)

    /** 하위 [bits]개 비트만 다른 해시 */
    private fun near(base: Long, bits: Int): Long = base xor ((1L shl bits) - 1)

    @Test
    fun burstChain_formsOneBurstGroup() {
        val rows = listOf(row(1, 0), row(2, 300), row(3, 650), row(4, 1_600)) // 간격 300, 350, 950
        val g = group(rows)
        assertThat(g).hasSize(1)
        assertThat(g[0].kind).isEqualTo(GroupKind.BURST)
        assertThat(g[0].memberIds).containsExactly(1L, 2L, 3L, 4L).inOrder()
        assertThat(g[0].startAt).isEqualTo(0)
        assertThat(g[0].endAt).isEqualTo(1_600)
    }

    @Test
    fun gapEqualToBurstGap_stillLinks() {
        assertThat(group(listOf(row(1, 0), row(2, 1_000)))).hasSize(1)
        assertThat(group(listOf(row(1, 0), row(2, 1_001)))).isEmpty() // dHash 없음 → 유사 연결도 없음
    }

    @Test
    fun similarLink_requiresBothHashes() {
        val h = 0x0F0F_F0F0_1234_5678L
        val withHash = group(listOf(row(1, 0, dhash = h), row(2, 5_000, dhash = near(h, 3))))
        assertThat(withHash).hasSize(1)
        assertThat(withHash[0].kind).isEqualTo(GroupKind.SIMILAR)

        assertThat(group(listOf(row(1, 0, dhash = h), row(2, 5_000, dhash = null)))).isEmpty()
        assertThat(group(listOf(row(1, 0, dhash = null), row(2, 5_000, dhash = h)))).isEmpty()
    }

    @Test
    fun similarLink_respectsDistanceAndGapLimits() {
        val h = 0x5555_AAAA_5555_AAAAL
        assertThat(group(listOf(row(1, 0, dhash = h), row(2, 5_000, dhash = near(h, 10))))).hasSize(1) // = 최대 거리
        assertThat(group(listOf(row(1, 0, dhash = h), row(2, 5_000, dhash = near(h, 11))))).isEmpty()
        assertThat(group(listOf(row(1, 0, dhash = h), row(2, 10_000, dhash = h)))).hasSize(1) // = 유사 간격
        assertThat(group(listOf(row(1, 0, dhash = h), row(2, 10_001, dhash = h)))).isEmpty()
    }

    @Test
    fun gapBreaksGroups_andSingletonsAreDropped() {
        val rows = listOf(
            row(1, 0), row(2, 500), // 연사 A
            row(3, 60_000), // 혼자
            row(4, 120_000), row(5, 120_400), row(6, 120_900), // 연사 B
            row(7, 200_000), // 혼자
        )
        val g = group(rows)
        assertThat(g.map { it.memberIds }).containsExactly(listOf(1L, 2L), listOf(4L, 5L, 6L)).inOrder()
        assertThat(g.flatMap { it.memberIds }).containsNoneOf(3L, 7L)
    }

    @Test
    fun kind_isBurstIfAnyBurstLink() {
        val h = 0x1234_5678_9ABC_DEF0L
        val rows = listOf(
            row(1, 0, dhash = h),
            row(2, 4_000, dhash = near(h, 2)), // 유사
            row(3, 4_300, dhash = near(h, 2)), // 연사
            row(4, 9_000, dhash = near(h, 4)), // 유사
        )
        val g = group(rows)
        assertThat(g).hasSize(1)
        assertThat(g[0].kind).isEqualTo(GroupKind.BURST)
        assertThat(g[0].memberIds).hasSize(4)
    }

    @Test
    fun best_isSharpest() {
        val g = group(listOf(row(1, 0, sharp = 50.0), row(2, 200, sharp = 300.0), row(3, 400, sharp = 120.0)))
        assertThat(g[0].bestId).isEqualTo(2L)
        assertThat(g[0].bestPinned).isFalse()
    }

    @Test
    fun best_missingSharpnessRanksLowest() {
        val g = group(listOf(row(1, 0, sharp = null), row(2, 200, sharp = 3.0)))
        assertThat(g[0].bestId).isEqualTo(2L)
    }

    @Test
    fun pinnedBest_isKeptWhenStillAMember() {
        val rows = listOf(row(1, 0, sharp = 50.0), row(2, 200, sharp = 300.0), row(3, 400, sharp = 120.0))
        val g = group(rows, pinned = setOf(3L))
        assertThat(g[0].bestId).isEqualTo(3L)
        assertThat(g[0].bestPinned).isTrue()
    }

    @Test
    fun pinnedBest_ignoredWhenNotAMember() {
        val rows = listOf(row(1, 0, sharp = 50.0), row(2, 200, sharp = 300.0), row(9, 90_000, sharp = 1.0))
        val g = group(rows, pinned = setOf(9L))
        assertThat(g).hasSize(1)
        assertThat(g[0].bestId).isEqualTo(2L)
        assertThat(g[0].bestPinned).isFalse()
    }

    @Test
    fun twoPinnedInMergedGroup_sharperPinnedWins() {
        val rows = listOf(row(1, 0, sharp = 80.0), row(2, 200, sharp = 300.0), row(3, 400, sharp = 120.0))
        val g = group(rows, pinned = setOf(1L, 3L))
        assertThat(g[0].bestId).isEqualTo(3L)
        assertThat(g[0].bestPinned).isTrue()
    }

    @Test
    fun sameTimestamp_orderedByPath_andTieGoesToFirst() {
        // SubSec이 없어 같은 초에 찍힌 연사: 파일 번호 순서
        val rows = listOf(
            row(10, 1_000, sharp = 200.0, path = "DCIM/100CANON/IMG_0003.JPG"),
            row(11, 1_000, sharp = 200.0, path = "DCIM/100CANON/IMG_0001.JPG"),
            row(12, 1_000, sharp = 100.0, path = "DCIM/100CANON/IMG_0002.JPG"),
        )
        val g = group(rows)
        assertThat(g[0].memberIds).containsExactly(11L, 12L, 10L).inOrder()
        assertThat(g[0].bestId).isEqualTo(11L) // 10과 동점 → 순서상 앞
    }

    @Test
    fun unsortedInput_isSorted() {
        val g = group(listOf(row(3, 800), row(1, 0), row(2, 400)))
        assertThat(g[0].memberIds).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun emptyAndSingleInput() {
        assertThat(group(emptyList())).isEmpty()
        assertThat(group(listOf(row(1, 0)))).isEmpty()
    }

    @Test
    fun similarChainCanSpanBeyondGapThroughIntermediateShots() {
        // 연결은 바로 앞 사진 기준: 5초 간격으로 계속 비슷한 장면을 찍으면 한 그룹
        val h = 0x0123_4567_89AB_CDEFL
        val rows = (0 until 6).map { row(it + 1L, it * 5_000L, dhash = near(h, it % 3)) }
        assertThat(group(rows).single().memberIds).hasSize(6)
    }

    // ---- 그룹 id 재사용 ----

    private fun planned(vararg ids: Long) = PlannedGroup(GroupKind.BURST, ids.toList(), ids.first(), false, 0, 0)

    @Test
    fun assignIds_reusesBestOverlappingExistingGroup() {
        val groups = listOf(planned(1, 2, 3), planned(4, 5))
        val previous = mapOf(1L to 100L, 2L to 100L, 3L to 101L, 4L to 101L, 5L to 101L)
        val ids = Grouping.assignIds(groups, previous, existingGroupIds = setOf(100L, 101L))
        assertThat(ids).containsExactly(100L, 101L).inOrder()
    }

    @Test
    fun assignIds_eachOldIdUsedOnce_andSplitGetsNewId() {
        // 기존 그룹 100이 둘로 갈라짐: 더 많이 겹치는 쪽이 100을 물려받고, 나머지는 새 id
        val groups = listOf(planned(1, 2), planned(3, 4, 5))
        val previous = (1L..5L).associateWith { 100L }
        val ids = Grouping.assignIds(groups, previous, setOf(100L))
        assertThat(ids).containsExactly(null, 100L).inOrder()
    }

    @Test
    fun assignIds_ignoresStaleIdsOfDeletedGroups() {
        val ids = Grouping.assignIds(listOf(planned(1, 2)), mapOf(1L to 7L, 2L to 7L), existingGroupIds = emptySet())
        assertThat(ids).containsExactly(null)
    }
}
