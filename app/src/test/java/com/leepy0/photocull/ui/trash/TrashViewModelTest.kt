package com.leepy0.photocull.ui.trash

import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.data.review.MediaCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private class FakeSource(scope: CoroutineScope) : TrashDataSource {
        val items = MutableStateFlow(TrashFakeData.items)
        val retention = MutableStateFlow(30)
        var failLoads = false
        var restoreResult: ((Collection<Long>) -> Int)? = null
        var restoreError: Exception? = null
        val restored = ArrayList<List<Long>>()
        val deleted = ArrayList<List<Long>>()
        var emptied = 0

        override fun trashed(): Flow<List<MediaCard>> =
            if (failLoads) flow { throw IllegalStateException("db closed") } else items

        override fun retentionDays(): Flow<Int> = retention
        override val workScope: CoroutineScope = scope

        override suspend fun restore(ids: Collection<Long>): Int {
            restoreError?.let { throw it }
            restored += ids.toList()
            val n = restoreResult?.invoke(ids) ?: ids.size
            items.value = items.value.filterNot { it.id in ids.toSet() }
            return n
        }

        override suspend fun deleteForever(ids: Collection<Long>): Int {
            deleted += ids.toList()
            items.value = items.value.filterNot { it.id in ids.toSet() }
            return ids.size
        }

        override suspend fun emptyTrash(): Int {
            emptied++
            val n = items.value.size
            items.value = emptyList()
            return n
        }

        override fun now(): Long = TRASH_NOW
    }

    /** 앱 범위처럼 SupervisorJob: 작업 하나가 실패해도 범위가 취소되지 않고 예외는 await로 온다 */
    private fun workScope() = CoroutineScope(SupervisorJob() + dispatcher)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.start(source: FakeSource): Pair<TrashViewModel, MutableList<TrashMessage>> {
        val vm = TrashViewModel(source)
        val messages = mutableListOf<TrashMessage>()
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        backgroundScope.launch(dispatcher) { vm.messages.collect { messages += it } }
        return vm to messages
    }

    @Test fun loadsItemsAndRetention() = runTest(dispatcher) {
        val source = FakeSource(workScope())
        val (vm, _) = start(source)

        val s = vm.state.value
        assertThat(s.phase).isEqualTo(TrashPhase.READY)
        assertThat(s.items).hasSize(TrashFakeData.items.size)
        assertThat(s.retentionDays).isEqualTo(30)
        assertThat(s.daysLeft(s.items.first())).isEqualTo(30)
    }

    @Test fun toggleAllSelectsThenClears() = runTest(dispatcher) {
        val source = FakeSource(workScope())
        val (vm, _) = start(source)

        vm.toggle(3)
        vm.toggleAll()
        assertThat(vm.state.value.allSelected).isTrue()
        vm.toggleAll()
        assertThat(vm.state.value.selection).isEmpty()
    }

    @Test fun restoreSelectedRestoresAndClearsSelection() = runTest(dispatcher) {
        val source = FakeSource(workScope())
        val (vm, messages) = start(source)

        vm.toggle(2)
        vm.toggle(5)
        vm.restoreSelected()

        assertThat(source.restored).containsExactly(listOf(2L, 5L))
        assertThat(messages).containsExactly(TrashMessage.Restored(count = 2, failed = 0))
        assertThat(vm.state.value.selection).isEmpty()
        assertThat(vm.state.value.items.map { it.id }).containsNoneOf(2L, 5L)
        assertThat(vm.state.value.busy).isFalse()
    }

    @Test fun partialAndFailedRestoreMessages() = runTest(dispatcher) {
        val source = FakeSource(workScope())
        val (vm, messages) = start(source)

        source.restoreResult = { ids -> ids.size - 1 }
        vm.toggle(1)
        vm.toggle(2)
        vm.restoreSelected()
        source.restoreResult = { 0 }
        vm.restoreOne(3)
        source.restoreError = IllegalStateException("io")
        vm.restoreOne(4)

        assertThat(messages).containsExactly(
            TrashMessage.Restored(count = 1, failed = 1),
            TrashMessage.RestoreFailed,
            TrashMessage.RestoreFailed,
        ).inOrder()
    }

    @Test fun deleteSelectedNeedsConfirmation() = runTest(dispatcher) {
        val source = FakeSource(workScope())
        val (vm, messages) = start(source)
        val picked = TrashFakeData.items.filter { it.id in setOf(3L, 4L) }

        vm.toggle(3)
        vm.toggle(4)
        vm.askDeleteSelected()

        val confirm = vm.state.value.confirm!!
        assertThat(confirm.ids).containsExactly(3L, 4L)
        assertThat(confirm.count).isEqualTo(2)
        assertThat(confirm.bytes).isEqualTo(picked.sumOf { it.bytes })
        assertThat(confirm.hasRaw).isFalse()
        assertThat(source.deleted).isEmpty() // 확인 전에는 지우지 않는다

        vm.dismissConfirm()
        assertThat(vm.state.value.confirm).isNull()
        assertThat(source.deleted).isEmpty()

        vm.askDeleteSelected()
        vm.confirmDelete()
        assertThat(source.deleted).containsExactly(listOf(3L, 4L))
        assertThat(messages).containsExactly(TrashMessage.Deleted(count = 2, failed = 0, bytes = picked.sumOf { it.bytes }))
        assertThat(vm.state.value.confirm).isNull()
        assertThat(vm.state.value.selection).isEmpty()
    }

    @Test fun emptyTrashAfterConfirmation() = runTest(dispatcher) {
        val source = FakeSource(workScope())
        val (vm, messages) = start(source)

        vm.askEmptyTrash()
        val confirm = vm.state.value.confirm!!
        assertThat(confirm.ids).isNull()
        assertThat(confirm.count).isEqualTo(TrashFakeData.items.size)
        assertThat(confirm.hasRaw).isTrue()

        vm.confirmDelete()
        assertThat(source.emptied).isEqualTo(1)
        assertThat(vm.state.value.isEmpty).isTrue()
        assertThat(messages.single()).isInstanceOf(TrashMessage.Deleted::class.java)
    }

    @Test fun loadErrorThenRetry() = runTest(dispatcher) {
        val source = FakeSource(workScope()).apply { failLoads = true }
        val (vm, _) = start(source)
        assertThat(vm.state.value.phase).isEqualTo(TrashPhase.ERROR)

        source.failLoads = false
        vm.retry()
        assertThat(vm.state.value.phase).isEqualTo(TrashPhase.READY)
    }

    @Test fun retentionZeroHasNoDaysLeft() = runTest(dispatcher) {
        val source = FakeSource(workScope()).apply { retention.value = 0 }
        val (vm, _) = start(source)
        val s = vm.state.value
        assertThat(s.items.map { s.daysLeft(it) }.toSet()).containsExactly(null)
    }
}
