package com.leepy0.photocull.ui.review

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.settings.BlurSensitivity
import com.leepy0.photocull.trash.TrashBatch
import com.leepy0.photocull.trash.TrashResult
import com.leepy0.photocull.ui.nav.ReviewTab
import kotlinx.coroutines.CompletableDeferred
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

    private class Harness(
        scope: TestScope,
        workScope: CoroutineScope,
        initialTab: ReviewTab = ReviewTab.BLUR,
        val handle: SavedStateHandle = SavedStateHandle(),
        val source: FakeReviewDataSource = FakeReviewDataSource(workScope = workScope),
    ) {
        val messages = mutableListOf<ReviewMessage>()
        val vm = ReviewViewModel(source, initialTab, handle) { messages += it }

        init {
            scope.backgroundScope.launch { vm.state.collect {} }
        }

        val state get() = vm.state.value
    }

    // 실제 앱 범위(SupervisorJob)처럼: 작업 하나가 실패해도 범위 전체가 취소되지 않게
    private fun TestScope.harness(tab: ReviewTab = ReviewTab.BLUR, handle: SavedStateHandle = SavedStateHandle()) =
        Harness(this, CoroutineScope(SupervisorJob() + dispatcher), tab, handle)

    @Test fun loadsAllListsAndStartsOnInitialTab() = runTest(dispatcher) {
        val h = harness(ReviewTab.BURST)
        assertThat(h.state.phase).isEqualTo(LoadPhase.READY)
        assertThat(h.state.tab).isEqualTo(ReviewTab.BURST)
        assertThat(h.state.count(ReviewTab.BLUR)).isEqualTo(FakeData.blur.size)
        assertThat(h.state.count(ReviewTab.BURST)).isEqualTo(FakeData.groups.size)
        assertThat(h.state.count(ReviewTab.VIDEO)).isEqualTo(FakeData.videos.size)
        assertThat(h.state.blurSelection).isEmpty()
        assertThat(h.state.videoSelection).isEmpty()
        assertThat(h.state.processedTabs).isEmpty()
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
        assertThat(h.state.processedTabs).containsExactly(ReviewTab.BLUR)
        val message = h.messages.single() as ReviewMessage.Trashed
        assertThat(message.moved).isEqualTo(2)
        assertThat(message.unit).isEqualTo(CountUnit.PHOTO)
        assertThat(message.undoable).isTrue()
        assertThat(message.regroupOnUndo).isFalse()

        h.vm.undo(message)
        advanceUntilIdle()
        assertThat(h.source.undoCalls).hasSize(1)
        assertThat(h.source.regroupCalls).isEqualTo(0)
        assertThat(h.state.blur.map { it.id }).containsAtLeast(1L, 4L)
        assertThat(h.state.blurSelection).containsExactly(1L, 4L)
    }

    @Test fun undoRestoresSelectionEvenWhenListUpdatesLate() = runTest(dispatcher) {
        val h = harness()
        h.vm.toggle(ReviewTab.BLUR, 2)
        h.vm.deleteSelected(ReviewTab.BLUR)
        advanceUntilIdle()
        val message = h.messages.single() as ReviewMessage.Trashed
        // 실제 DB는 되돌린 뒤 조금 늦게 목록을 다시 내보낸다: 그사이 2번이 없는 목록이 와도 선택을 잃지 않는다
        h.source.publishOnUndo = false
        val withoutTwo = h.source.blurFlow.value
        h.vm.undo(message)
        advanceUntilIdle()
        h.source.blurFlow.value = withoutTwo.filter { it.id != 5L }
        assertThat(h.state.blurSelection).isEmpty()
        h.source.blurFlow.value = FakeData.blur
        assertThat(h.state.blurSelection).containsExactly(2L)
    }

    @Test fun keepRemainingKeepsTabItemsAndUndoUnkeeps() = runTest(dispatcher) {
        val h = harness(ReviewTab.VIDEO)
        h.vm.keepRemaining(ReviewTab.VIDEO)
        advanceUntilIdle()
        assertThat(h.source.keepCalls.single()).containsExactlyElementsIn(FakeData.videos.map { it.id })
        assertThat(h.state.videos).isEmpty()
        assertThat(h.state.processedTabs).containsExactly(ReviewTab.VIDEO)
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

    @Test fun bestOfAllGroupsIsOneTrashCallLeavesBlurryBestPendingAndRegroups() = runTest(dispatcher) {
        val h = harness(ReviewTab.BURST)
        val plan = h.state.burstPlan
        val expectedDelete = FakeData.groups.flatMap { it.defaultSelection() }
        // 전체가 흐린 그룹(300)의 베스트는 '유지'로 확정하지 않는다
        val expectedKeep = FakeData.groups.filterNot { it.allBlurry }.map { it.bestId }
        assertThat(plan.deleteIds).containsExactlyElementsIn(expectedDelete)
        assertThat(plan.keepIds).containsExactlyElementsIn(expectedKeep)
        assertThat(plan.keepIds).doesNotContain(303L)
        assertThat(plan.blurryGroupCount).isEqualTo(1)
        assertThat(plan.deleteBytes).isEqualTo(expectedDelete.size * 30 * MB)

        h.vm.keepBestOfAllGroups()
        advanceUntilIdle()
        assertThat(h.source.trashCalls).hasSize(1)
        val (deleted, kept) = h.source.trashCalls.single()
        assertThat(deleted).containsExactlyElementsIn(expectedDelete)
        assertThat(kept).containsExactlyElementsIn(expectedKeep)
        // 혼자 남은 흐린 베스트가 흐림 탭으로 가도록 그룹을 다시 묶는다
        assertThat(h.source.regroupCalls).isEqualTo(1)
        assertThat(h.state.processedTabs).containsExactly(ReviewTab.BURST)

        val message = h.messages.single() as ReviewMessage.Trashed
        assertThat(message.moved).isEqualTo(expectedDelete.size)
        assertThat(message.regroupOnUndo).isTrue()
        h.vm.undo(message)
        advanceUntilIdle()
        assertThat(h.state.groups).hasSize(FakeData.groups.size)
        assertThat(h.source.regroupCalls).isEqualTo(2)
    }

    @Test fun burstPlanNeverDeletesBestOrKeptPhotos() {
        val g = group(10, listOf(11L, 12L, 13L, 14L), bestId = 12, keptIds = setOf(13L))
        val plan = BurstPlan.of(listOf(g))
        assertThat(plan.deleteIds).containsExactly(11L, 14L)
        assertThat(plan.keepIds).containsExactly(12L)
        assertThat(plan.blurryGroupCount).isEqualTo(0)
    }

    @Test fun burstPlanLeavesBestOfAllBlurryGroupPending() {
        val g = group(10, listOf(11L, 12L, 13L), bestId = 12, allBlurry = true)
        val plan = BurstPlan.of(listOf(g))
        assertThat(plan.deleteIds).containsExactly(11L, 13L)
        assertThat(plan.keepIds).isEmpty()
        assertThat(plan.blurryGroupCount).isEqualTo(1)
    }

    @Test fun trashResultWithNothingMovedIsAFailureWithoutUndo() {
        // 옮긴 수가 0이면 batch에 무엇이 있든 '옮겼습니다'라고 하지 않는다
        val result = TrashResult(TrashBatch(listOf(1L, 2L, 3L)), movedCount = 0, failedCount = 0, freedBytes = 0)
        val message = ReviewMessage.fromTrash(result, CountUnit.PHOTO, null)
        assertThat(message).isEqualTo(ReviewMessage.TrashFailed)
        assertThat(message.undoable).isFalse()

        val partial = ReviewMessage.fromTrash(TrashResult(TrashBatch(listOf(1L)), 1, 2, 10), CountUnit.PHOTO, null) as ReviewMessage.Trashed
        assertThat(partial.moved).isEqualTo(1)
        assertThat(partial.failed).isEqualTo(2)
    }

    @Test fun failureShowsMessageAndClearsBusy() = runTest(dispatcher) {
        val h = harness()
        h.source.failTrash = true
        h.vm.toggle(ReviewTab.BLUR, 2)
        h.vm.deleteSelected(ReviewTab.BLUR)
        advanceUntilIdle()
        assertThat(h.messages.single()).isEqualTo(ReviewMessage.ActionFailed)
        assertThat(h.state.busy).isFalse()
        assertThat(h.state.processedTabs).isEmpty()
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

    @Test fun selectionOfVanishedItemsIsPrunedAndDoesNotComeBack() = runTest(dispatcher) {
        val h = harness()
        h.vm.toggle(ReviewTab.BLUR, 8)
        h.vm.toggle(ReviewTab.BLUR, 9)
        h.vm.toggle(ReviewTab.BLUR, 10)
        // 민감도를 낮춰 9, 10번이 후보에서 빠진 상황
        h.source.blurFlow.value = FakeData.blur.filter { it.id < 9 }
        assertThat(h.state.blurSelection).containsExactly(8L)
        assertThat(h.state.selectedBytes(ReviewTab.BLUR)).isEqualTo(12 * MB)
        // 민감도를 되돌려 다시 나타나도 선택된 채로 나오지 않는다
        h.source.blurFlow.value = FakeData.blur
        assertThat(h.state.blurSelection).containsExactly(8L)
    }

    @Test fun tabSelectionAndProgressSurviveInSavedState() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val first = harness(ReviewTab.BLUR, handle)
        first.vm.selectTab(ReviewTab.VIDEO)
        first.vm.toggle(ReviewTab.BLUR, 3)
        first.vm.toggle(ReviewTab.VIDEO, 52)
        first.vm.keepRemaining(ReviewTab.BURST)
        advanceUntilIdle()

        // 하단 탭을 오가거나 프로세스가 다시 떠서 ViewModel을 새로 만든 경우
        val second = harness(ReviewTab.BLUR, handle)
        assertThat(second.state.tab).isEqualTo(ReviewTab.VIDEO)
        assertThat(second.state.blurSelection).containsExactly(3L)
        assertThat(second.state.videoSelection).containsExactly(52L)
        assertThat(second.state.processedTabs).containsExactly(ReviewTab.BURST)
    }

    @Test fun undoWhileBusyIsKeptForLater() = runTest(dispatcher) {
        val h = harness()
        h.vm.toggle(ReviewTab.BLUR, 1)
        h.vm.deleteSelected(ReviewTab.BLUR)
        advanceUntilIdle()
        val first = h.messages.single() as ReviewMessage.Trashed

        // 다음 삭제가 도는 중에 이전 결과의 [실행 취소]를 누름
        val gate = CompletableDeferred<Unit>()
        h.source.trashGate = gate
        h.vm.toggle(ReviewTab.BLUR, 2)
        h.vm.deleteSelected(ReviewTab.BLUR)
        assertThat(h.state.busy).isTrue()
        h.vm.undo(first)
        assertThat(h.source.undoCalls).isEmpty()
        // 결과를 다시 올려 나중에 누를 수 있게 한다
        assertThat(h.messages.last()).isEqualTo(first)

        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(h.state.busy).isFalse()
        assertThat(h.messages.last()).isInstanceOf(ReviewMessage.Trashed::class.java)
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
}
