package com.leepy0.photocull.ui.review

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        val vm = GroupViewModel(source, groupId)
        val finished = mutableListOf<ReviewMessage>()
        val messages = mutableListOf<ReviewMessage>()

        init {
            scope.backgroundScope.launch { vm.state.collect {} }
            scope.backgroundScope.launch { vm.finished.collect { finished += it } }
            scope.backgroundScope.launch { vm.messages.collect { messages += it } }
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

    @Test fun toggleAndMarkChangeSelection() = runTest(dispatcher) {
        val h = Harness(this)
        h.vm.toggle(101)
        assertThat(h.content.selection).doesNotContain(101L)
        h.vm.setMarked(105, true)
        assertThat(h.content.selection).contains(105L)
        h.vm.setMarked(105, false)
        assertThat(h.content.selection).doesNotContain(105L)
    }

    @Test fun showChangesCurrentFrame() = runTest(dispatcher) {
        val h = Harness(this)
        h.vm.show(110)
        assertThat(h.content.current.id).isEqualTo(110L)
        assertThat(h.content.currentIndex).isEqualTo(9)
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
        h.vm.toggle(101) // 사용자가 손댐: 101은 남기기
        h.vm.pinBest(108)
        advanceUntilIdle()
        assertThat(h.content.selection).doesNotContain(108L)
        assertThat(h.content.selection).doesNotContain(101L)
        // 이전 베스트는 사용자가 고르지 않았으므로 그대로 미선택
        assertThat(h.content.selection).doesNotContain(105L)
    }

    @Test fun deleteTrashesSelectionKeepsRestAndFinishes() = runTest(dispatcher) {
        val h = Harness(this)
        h.vm.toggle(112) // 112는 남기기로
        val expectedDelete = (101L..111L).filter { it != 105L }
        h.vm.deleteSelected()
        advanceUntilIdle()

        val (deleted, kept) = h.source.trashCalls.single()
        assertThat(deleted).containsExactlyElementsIn(expectedDelete)
        assertThat(kept).containsExactly(105L, 112L)
        val message = h.finished.single() as ReviewMessage.Trashed
        assertThat(message.moved).isEqualTo(expectedDelete.size)
        assertThat(message.batch.keptIds).containsExactly(105L, 112L)
        // 정리한 뒤 그룹이 사라져도 '이미 정리됨' 화면을 보이지 않는다
        assertThat(h.vm.state.value).isEqualTo(GroupUiState.Loading)
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
