package com.leepy0.photocull.ui.review

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.trash.TrashBatch
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
class GroupViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** 그룹 100: 101..112, 베스트 105 */
    private inner class Harness(scope: TestScope, groupId: Long = 100) {
        val source = FakeReviewDataSource(workScope = CoroutineScope(SupervisorJob() + dispatcher))
        val messages = mutableListOf<ReviewMessage>()
        val store = ViewModelStore()
        val vm: GroupViewModel = ViewModelProvider.create(
            store,
            viewModelFactory { initializer { GroupViewModel(source, groupId) { messages += it } } },
        )[GroupViewModel::class]
        val finished = mutableListOf<ReviewMessage>()

        init {
            scope.backgroundScope.launch { vm.state.collect {} }
            scope.backgroundScope.launch { vm.finished.collect { finished += it } }
        }

        val content get() = vm.state.value as GroupUiState.Content
    }

    @Test fun defaultSelectionIsEverythingButBestAndOpensOnBest() = runTest(dispatcher) {
        val h = Harness(this)
        assertThat(h.content.currentId).isEqualTo(105L)
        assertThat(h.content.selection).containsExactlyElementsIn((101L..112L).filter { it != 105L })
        assertThat(h.content.selectedBytes).isEqualTo(11 * 30 * MB)
        assertThat(h.content.keepIds).containsExactly(105L)
    }

    @Test fun toggleAndMarkChangeSelectionButNeverTheBest() = runTest(dispatcher) {
        val h = Harness(this)
        h.vm.toggle(101)
        assertThat(h.content.selection).doesNotContain(101L)
        h.vm.setMarked(101, true)
        assertThat(h.content.selection).contains(101L)
        // 베스트는 지울 수 없다(먼저 다른 사진을 베스트로 지정)
        h.vm.setMarked(105, true)
        assertThat(h.content.selection).doesNotContain(105L)
        h.vm.toggle(105)
        assertThat(h.content.selection).doesNotContain(105L)
    }

    @Test fun showChangesCurrentFrame() = runTest(dispatcher) {
        val h = Harness(this)
        h.vm.show(110)
        assertThat(h.content.current.id).isEqualTo(110L)
        assertThat(h.content.currentIndex).isEqualTo(9)
    }

    @Test fun untouchedSelectionFollowsGroupChanges() = runTest(dispatcher) {
        val h = Harness(this)
        // 분석이 그룹에 사진을 더하고(113) 베스트를 다시 고름(108)
        val g = h.source.groupsFlow.value.first { it.id == 100L }
        val grown = g.copy(members = g.members + photo(113, groupId = 100, bytes = 30 * MB), bestId = 108)
        h.source.groupsFlow.value = listOf(grown) + h.source.groupsFlow.value.filter { it.id != 100L }
        assertThat(h.content.selection).contains(113L)
        assertThat(h.content.selection).contains(105L)
        assertThat(h.content.selection).doesNotContain(108L)
        assertThat(h.content.keepIds).containsExactly(108L)
    }

    @Test fun editedSelectionIsNarrowedToMembersAndNeverHoldsTheNewBest() = runTest(dispatcher) {
        val h = Harness(this)
        h.vm.toggle(101) // 사용자가 손댐: 101은 유지
        val g = h.source.groupsFlow.value.first { it.id == 100L }
        // 112가 그룹에서 빠지고 113이 들어오며, 베스트가 108로 바뀜
        val changed = g.copy(members = g.members.filter { it.id != 112L } + photo(113, groupId = 100), bestId = 108)
        h.source.groupsFlow.value = listOf(changed) + h.source.groupsFlow.value.filter { it.id != 100L }
        assertThat(h.content.selection).containsNoneOf(101L, 108L, 112L)
        // 손댄 선택은 그대로(이전 베스트 105는 사용자가 고르지 않았으므로 미선택)
        assertThat(h.content.selection).containsExactly(102L, 103L, 104L, 106L, 107L, 109L, 110L, 111L)
    }

    @Test fun pinBestWithUntouchedSelectionMovesDefaultSelection() = runTest(dispatcher) {
        val h = Harness(this)
        h.vm.pinBest(108)
        advanceUntilIdle()
        assertThat(h.source.pinCalls).containsExactly(100L to 108L)
        assertThat(h.content.group.bestId).isEqualTo(108L)
        assertThat(h.content.selection).containsExactlyElementsIn((101L..112L).filter { it != 108L })
    }

    @Test fun pinBestWithCustomSelectionOnlyUnselectsNewBest() = runTest(dispatcher) {
        val h = Harness(this)
        h.vm.toggle(101) // 사용자가 손댐: 101은 유지
        h.vm.pinBest(108)
        advanceUntilIdle()
        assertThat(h.content.selection).doesNotContain(108L)
        assertThat(h.content.selection).doesNotContain(101L)
        // 이전 베스트는 사용자가 고르지 않았으므로 그대로 미선택
        assertThat(h.content.selection).doesNotContain(105L)
    }

    @Test fun deleteTrashesSelectionKeepsRestAndFinishes() = runTest(dispatcher) {
        val h = Harness(this)
        h.vm.toggle(112) // 112는 유지로
        val expectedDelete = (101L..111L).filter { it != 105L }
        h.vm.deleteSelected()
        advanceUntilIdle()

        val (deleted, kept) = h.source.trashCalls.single()
        assertThat(deleted).containsExactlyElementsIn(expectedDelete)
        assertThat(kept).containsExactly(105L, 112L)
        val message = h.messages.single() as ReviewMessage.Trashed
        assertThat(message.moved).isEqualTo(expectedDelete.size)
        assertThat(message.batch.keptIds).containsExactly(105L, 112L)
        assertThat(h.finished.single()).isEqualTo(message)
        // 정리한 뒤 그룹이 사라져도 '이미 정리됨' 화면을 보이지 않는다
        assertThat(h.vm.state.value).isEqualTo(GroupUiState.Loading)
    }

    @Test fun resultReachesSharedSnackbarEvenIfScreenClosesMidDelete() = runTest(dispatcher) {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.source.trashGate = gate
        h.vm.deleteSelected()
        // 지우는 중에 시스템 뒤로 가기로 화면(ViewModel)이 사라짐
        h.store.clear()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(h.source.trashCalls).hasSize(1)
        assertThat(h.messages.single()).isInstanceOf(ReviewMessage.Trashed::class.java)
    }

    @Test fun keepAllKeepsPendingAndFinishes() = runTest(dispatcher) {
        val h = Harness(this)
        (101L..112L).forEach { if (it != 105L) h.vm.toggle(it) }
        assertThat(h.content.selection).isEmpty()
        h.vm.keepAll()
        advanceUntilIdle()
        assertThat(h.source.keepCalls.single()).containsExactlyElementsIn((101L..112L).toList())
        val message = h.finished.single() as ReviewMessage.Kept
        assertThat(message.ids).hasSize(12)
        assertThat(h.messages.single()).isEqualTo(message)
    }

    @Test fun undoFromGroupScreenRevertsBatch() = runTest(dispatcher) {
        val h = Harness(this)
        val earlier = ReviewMessage.Trashed(2, 0, CountUnit.PHOTO, TrashBatch(listOf(1L, 2L)), regroupOnUndo = true)
        h.vm.undo(earlier)
        advanceUntilIdle()
        assertThat(h.source.undoCalls.single()).isEqualTo(earlier.batch)
        assertThat(h.source.regroupCalls).isEqualTo(1)
    }

    @Test fun groupRemovedElsewhereShowsMissing() = runTest(dispatcher) {
        val h = Harness(this)
        h.source.groupsFlow.value = h.source.groupsFlow.value.filter { it.id != 100L }
        assertThat(h.vm.state.value).isEqualTo(GroupUiState.Missing)
    }

    @Test fun totalTrashFailureStaysOnScreen() = runTest(dispatcher) {
        val h = Harness(this)
        h.source.failedPerCall = 100
        h.vm.deleteSelected()
        advanceUntilIdle()
        assertThat(h.finished).isEmpty()
        assertThat(h.messages.single()).isEqualTo(ReviewMessage.TrashFailed)
        assertThat(h.content.busy).isFalse()
    }

    @Test fun exceptionStaysOnScreenWithMessage() = runTest(dispatcher) {
        val h = Harness(this)
        h.source.failTrash = true
        h.vm.deleteSelected()
        advanceUntilIdle()
        assertThat(h.finished).isEmpty()
        assertThat(h.messages.single()).isEqualTo(ReviewMessage.ActionFailed)
    }
}
