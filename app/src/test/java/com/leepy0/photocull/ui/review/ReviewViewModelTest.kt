package com.leepy0.photocull.ui.review

import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.settings.BlurSensitivity
import com.leepy0.photocull.ui.nav.ReviewTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Harness(scope: TestScope, workScope: CoroutineScope, initialTab: ReviewTab = ReviewTab.BLUR) {
        val source = FakeReviewDataSource(workScope = workScope)
        val vm = ReviewViewModel(source, initialTab)
        val messages = mutableListOf<ReviewMessage>()

        init {
            scope.backgroundScope.launch { vm.state.collect {} }
            scope.backgroundScope.launch { vm.messages.collect { messages += it } }
        }

        val state get() = vm.state.value
    }

    // 실제 앱 범위(SupervisorJob)처럼: 작업 하나가 실패해도 범위 전체가 취소되지 않게
    private fun TestScope.harness(tab: ReviewTab = ReviewTab.BLUR) = Harness(this, CoroutineScope(SupervisorJob() + dispatcher), tab)

    @Test fun loadsAllListsAndStartsOnInitialTab() = runTest(dispatcher) {
        val h = harness(ReviewTab.BURST)
        assertThat(h.state.phase).isEqualTo(LoadPhase.READY)
        assertThat(h.state.tab).isEqualTo(ReviewTab.BURST)
        assertThat(h.state.count(ReviewTab.BLUR)).isEqualTo(FakeData.blur.size)
        assertThat(h.state.count(ReviewTab.BURST)).isEqualTo(FakeData.groups.size)
        assertThat(h.state.count(ReviewTab.VIDEO)).isEqualTo(FakeData.videos.size)
        assertThat(h.state.blurSelection).isEmpty()
        assertThat(h.state.videoSelection).isEmpty()
    }

    @Test fun selectionIsPerTabAndBytesAreSummed() = runTest(dispatcher) {
        val h = harness()
        h.vm.toggle(ReviewTab.BLUR, 1)
        h.vm.toggle(ReviewTab.BLUR, 2)
        h.vm.toggle(ReviewTab.VIDEO, 54)
        assertThat(h.state.blurSelection).containsExactly(1L, 2L)
        assertThat(h.state.videoSelection).containsExactly(54L)
        assertThat(h.state.selectedBytes(ReviewTab.BLUR)).isEqualTo(33 * MB + 12 * MB)
        assertThat(h.state.selectedBytes(ReviewTab.VIDEO)).isEqualTo(640 * MB)

        h.vm.toggle(ReviewTab.BLUR, 1)
        assertThat(h.state.blurSelection).containsExactly(2L)
    }

    @Test fun toggleAllSelectsEverythingThenClears() = runTest(dispatcher) {
        val h = harness()
        h.vm.toggle(ReviewTab.BLUR, 3)
        h.vm.toggleAll(ReviewTab.BLUR)
        assertThat(h.state.blurSelection).hasSize(FakeData.blur.size)
        h.vm.toggleAll(ReviewTab.BLUR)
        assertThat(h.state.blurSelection).isEmpty()
    }

    @Test fun sectionToggleOnlyTouchesThatSection() = runTest(dispatcher) {
        val h = harness(ReviewTab.VIDEO)
        val shortIds = h.state.shortVideos.map { it.id }
        assertThat(shortIds).containsExactly(51L, 52L, 53L)
        h.vm.toggleGroupOfIds(ReviewTab.VIDEO, shortIds)
        assertThat(h.state.videoSelection).containsExactlyElementsIn(shortIds)
        h.vm.toggleGroupOfIds(ReviewTab.VIDEO, shortIds)
        assertThat(h.state.videoSelection).isEmpty()
    }

    @Test fun deleteMovesSelectionToTrashAndUndoRestoresItemsAndSelection() = runTest(dispatcher) {
        val h = harness()
        h.vm.toggle(ReviewTab.BLUR, 1)
        h.vm.toggle(ReviewTab.BLUR, 4)
        h.vm.deleteSelected(ReviewTab.BLUR)
        advanceUntilIdle()

        assertThat(h.source.trashCalls).containsExactly(listOf(1L, 4L) to emptyList<Long>())
        assertThat(h.state.blur.map { it.id }).containsNoneOf(1L, 4L)
        assertThat(h.state.blurSelection).isEmpty()
        assertThat(h.state.busy).isFalse()
        val message = h.messages.single() as ReviewMessage.Trashed
        assertThat(message.moved).isEqualTo(2)
        assertThat(message.unit).isEqualTo(CountUnit.PHOTO)
        assertThat(message.undoable).isTrue()

        h.vm.undo(message)
        advanceUntilIdle()
        assertThat(h.source.undoCalls).hasSize(1)
        assertThat(h.state.blur.map { it.id }).containsAtLeast(1L, 4L)
        assertThat(h.state.blurSelection).containsExactly(1L, 4L)
    }

    @Test fun keepRemainingKeepsTabItemsAndUndoUnkeeps() = runTest(dispatcher) {
        val h = harness(ReviewTab.VIDEO)
        h.vm.keepRemaining(ReviewTab.VIDEO)
        advanceUntilIdle()
        assertThat(h.source.keepCalls.single()).containsExactlyElementsIn(FakeData.videos.map { it.id })
        assertThat(h.state.videos).isEmpty()
        val message = h.messages.single() as ReviewMessage.Kept
        assertThat(message.unit).isEqualTo(CountUnit.VIDEO)
        assertThat(message.ids).hasSize(FakeData.videos.size)

        h.vm.undo(message)
        advanceUntilIdle()
        assertThat(h.source.unkeepCalls.single()).containsExactlyElementsIn(message.ids)
        assertThat(h.state.videos).hasSize(FakeData.videos.size)
    }

    @Test fun keepRemainingOnBurstTabKeepsOnlyPendingGroupPhotos() = runTest(dispatcher) {
        val h = harness(ReviewTab.BURST)
        h.vm.keepRemaining(ReviewTab.BURST)
        advanceUntilIdle()
        val expected = FakeData.groups.flatMap { g -> g.members.filter { it.reviewState == ReviewState.PENDING } }.map { it.id }
        assertThat(h.source.keepCalls.single()).containsExactlyElementsIn(expected)
        assertThat(h.state.groups).isEmpty()
    }

    @Test fun bestOfAllGroupsIsOneTrashCallWithKeepIds() = runTest(dispatcher) {
        val h = harness(ReviewTab.BURST)
        val plan = h.state.burstPlan
        val expectedDelete = FakeData.groups.flatMap { it.defaultSelection() }
        val expectedKeep = FakeData.groups.map { it.bestId }
        assertThat(plan.deleteIds).containsExactlyElementsIn(expectedDelete)
        assertThat(plan.keepIds).containsExactlyElementsIn(expectedKeep)
        assertThat(plan.deleteBytes).isEqualTo(expectedDelete.size * 30 * MB)

        h.vm.keepBestOfAllGroups()
        advanceUntilIdle()
        assertThat(h.source.trashCalls).hasSize(1)
        val (deleted, kept) = h.source.trashCalls.single()
        assertThat(deleted).containsExactlyElementsIn(expectedDelete)
        assertThat(kept).containsExactlyElementsIn(expectedKeep)
        assertThat(h.state.groups).isEmpty()

        val message = h.messages.single() as ReviewMessage.Trashed
        assertThat(message.moved).isEqualTo(expectedDelete.size)
        h.vm.undo(message)
        advanceUntilIdle()
        assertThat(h.state.groups).hasSize(FakeData.groups.size)
    }

    @Test fun burstPlanNeverDeletesBestOrKeptPhotos() {
        val g = group(10, listOf(11L, 12L, 13L, 14L), bestId = 12, keptIds = setOf(13L))
        val plan = BurstPlan.of(listOf(g))
        assertThat(plan.deleteIds).containsExactly(11L, 14L)
        assertThat(plan.keepIds).containsExactly(12L)
    }

    @Test fun failureShowsMessageAndClearsBusy() = runTest(dispatcher) {
        val h = harness()
        h.source.failTrash = true
        h.vm.toggle(ReviewTab.BLUR, 2)
        h.vm.deleteSelected(ReviewTab.BLUR)
        advanceUntilIdle()
        assertThat(h.messages.single()).isEqualTo(ReviewMessage.ActionFailed)
        assertThat(h.state.busy).isFalse()
        // 실패하면 선택은 그대로(다시 시도할 수 있게)
        assertThat(h.state.blurSelection).containsExactly(2L)
    }

    @Test fun partialFailureReportsBothCounts() = runTest(dispatcher) {
        val h = harness()
        h.source.failedPerCall = 1
        h.vm.toggle(ReviewTab.BLUR, 1)
        h.vm.toggle(ReviewTab.BLUR, 2)
        h.vm.toggle(ReviewTab.BLUR, 3)
        h.vm.deleteSelected(ReviewTab.BLUR)
        advanceUntilIdle()
        val message = h.messages.single() as ReviewMessage.Trashed
        assertThat(message.moved).isEqualTo(2)
        assertThat(message.failed).isEqualTo(1)
        // 못 옮긴 3번은 목록과 선택에 남아 다시 시도할 수 있다
        assertThat(h.state.blur.map { it.id }).contains(3L)
        assertThat(h.state.blurSelection).containsExactly(3L)
    }

    @Test fun totalFailureKeepsSelectionAndHasNoUndo() = runTest(dispatcher) {
        val h = harness()
        h.source.failedPerCall = 100
        h.vm.toggle(ReviewTab.BLUR, 1)
        h.vm.deleteSelected(ReviewTab.BLUR)
        advanceUntilIdle()
        assertThat(h.messages.single()).isEqualTo(ReviewMessage.TrashFailed)
        assertThat(h.messages.single().undoable).isFalse()
        assertThat(h.state.blurSelection).containsExactly(1L)
    }

    @Test fun selectionOfVanishedItemsIsDroppedFromState() = runTest(dispatcher) {
        val h = harness()
        h.vm.toggle(ReviewTab.BLUR, 9)
        h.vm.toggle(ReviewTab.BLUR, 10)
        // 민감도를 낮춰 9, 10번이 후보에서 빠진 상황
        h.source.blurFlow.value = FakeData.blur.filter { it.id < 9 }
        assertThat(h.state.blurSelection).isEmpty()
        assertThat(h.state.selectedBytes(ReviewTab.BLUR)).isEqualTo(0)
    }

    @Test fun sensitivityChangeIsSaved() = runTest(dispatcher) {
        val h = harness()
        h.vm.setSensitivity(BlurSensitivity.HIGH)
        advanceUntilIdle()
        assertThat(h.state.sensitivity).isEqualTo(BlurSensitivity.HIGH)
    }

    @Test fun nextTabSkipsEmptyTabsAndWraps() = runTest(dispatcher) {
        val h = harness(ReviewTab.VIDEO)
        h.source.blurFlow.value = emptyList()
        assertThat(h.state.nextTabWithItems(ReviewTab.BLUR)).isEqualTo(ReviewTab.BURST)
        assertThat(h.state.nextTabWithItems(ReviewTab.VIDEO)).isEqualTo(ReviewTab.BURST)
        h.source.groupsFlow.value = emptyList()
        h.source.videosFlow.value = emptyList()
        assertThat(h.state.nextTabWithItems(ReviewTab.BLUR)).isNull()
        assertThat(h.state.allDone).isTrue()
    }

    @Test fun analysisStatusIsExposed() = runTest(dispatcher) {
        val h = harness()
        h.source.analysisFlow.value = AnalysisStatus(running = true, done = 12, total = 40)
        assertThat(h.state.analysis.running).isTrue()
        assertThat(h.state.analysis.done).isEqualTo(12)
    }

    @Test fun groupScreenResultIsForwardedAsSnackbar() = runTest(dispatcher) {
        val h = harness(ReviewTab.BURST)
        val external = ReviewMessage.Kept(listOf(1L, 2L), CountUnit.PHOTO)
        h.vm.showExternal(external)
        assertThat(h.messages.single()).isEqualTo(external)
    }
}
