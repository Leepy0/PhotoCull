package com.leepy0.photocull.ui.importer

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.importer.ImportError
import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.storage.CardState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val card = CardState.Ready(ImportFakes.volume)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun opensWithCardConnected_scansOnce() = runTest(dispatcher) {
        val source = FakeImportFlowSource(card = card)
        val vm = ImportViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        assertThat(source.scans).isEqualTo(1)
        assertThat(vm.state.value.view).isEqualTo(ImportView.Checking)
    }

    @Test fun cardConnectedLater_scans() = runTest(dispatcher) {
        val source = FakeImportFlowSource()
        val vm = ImportViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        assertThat(source.scans).isEqualTo(0)
        assertThat(vm.state.value.view).isEqualTo(ImportView.NoCard())
        source.cardFlow.value = card
        assertThat(source.scans).isEqualTo(1)
    }

    @Test fun start_usesSettingsDefault_thenScreenChoice() = runTest(dispatcher) {
        val source = FakeImportFlowSource(card = card, import = ImportState.Ready(ImportFakes.summary()))
        source.settingsFlow.value = source.settingsFlow.value.copy(moveMode = false)
        val vm = ImportViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        assertThat(vm.state.value.moveMode).isFalse()
        vm.start()
        vm.setMoveMode(true)
        assertThat(vm.state.value.moveMode).isTrue()
        vm.start()
        assertThat(source.starts).containsExactly(false, true).inOrder()
    }

    @Test fun start_refusedWithoutSpaceOrNewFiles() = runTest(dispatcher) {
        val source = FakeImportFlowSource(card = card, import = ImportState.Ready(ImportFakes.summary(freeBytes = 1 * GB)))
        val vm = ImportViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        vm.start()
        source.importFlow.value = ImportState.Ready(ImportFakes.summary(newFiles = 0, newBytes = 0))
        vm.start()
        assertThat(source.starts).isEmpty()
    }

    @Test fun cancel_marksCancellingUntilImportStops() = runTest(dispatcher) {
        val source = FakeImportFlowSource(card = card, import = ImportState.Running(ImportFakes.progress()))
        val vm = ImportViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        vm.cancel()
        assertThat(source.cancels).isEqualTo(1)
        assertThat(vm.state.value.cancelRequested).isTrue()
        source.importFlow.value = ImportState.Finished(ImportFakes.result(cancelled = true))
        assertThat(vm.state.value.cancelRequested).isFalse()
        // 다음 가져오기에서는 다시 누를 수 있다
        source.importFlow.value = ImportState.Running(ImportFakes.progress())
        assertThat(vm.state.value.cancelRequested).isFalse()
    }

    @Test fun cancel_ignoredWhenNotRunning() = runTest(dispatcher) {
        val source = FakeImportFlowSource(card = card, import = ImportState.Ready(ImportFakes.summary()))
        val vm = ImportViewModel(source)
        vm.cancel()
        assertThat(source.cancels).isEqualTo(0)
    }

    @Test fun dismissResult_resetsOnlyResultsAndErrors() = runTest(dispatcher) {
        val source = FakeImportFlowSource(card = CardState.NotConnected, import = ImportState.Running(ImportFakes.progress()))
        val vm = ImportViewModel(source)
        vm.dismissResult()
        assertThat(source.resets).isEqualTo(0)
        source.importFlow.value = ImportState.Ready(ImportFakes.summary())
        vm.dismissResult()
        assertThat(source.resets).isEqualTo(0)
        source.importFlow.value = ImportState.Finished(ImportFakes.result())
        vm.dismissResult()
        assertThat(source.resets).isEqualTo(1)
        source.importFlow.value = ImportState.Failed(ImportError.CardRemoved(1, 2))
        vm.dismissResult()
        assertThat(source.resets).isEqualTo(2)
    }

    @Test fun afterFinishedAndReset_cardStillConnected_rescans() = runTest(dispatcher) {
        val source = FakeImportFlowSource(card = card, import = ImportState.Finished(ImportFakes.result()))
        val vm = ImportViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        assertThat(source.scans).isEqualTo(0)
        vm.dismissResult()
        assertThat(source.scans).isEqualTo(1)
    }

    @Test fun resume_recalculatesSpaceOnlyWhenShort() = runTest(dispatcher) {
        val source = FakeImportFlowSource(card = card, import = ImportState.Ready(ImportFakes.summary()))
        val vm = ImportViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        vm.onResume()
        assertThat(source.scans).isEqualTo(0)
        source.importFlow.value = ImportState.Ready(ImportFakes.summary(freeBytes = 1 * GB))
        vm.onResume()
        assertThat(source.scans).isEqualTo(1)
    }

    @Test fun rescan_withoutCard_refreshesMonitor() = runTest(dispatcher) {
        val source = FakeImportFlowSource(card = CardState.NotConnected, import = ImportState.Failed(ImportError.CardRemoved(1, 2)))
        val vm = ImportViewModel(source)
        vm.rescan()
        assertThat(source.scans).isEqualTo(0)
        assertThat(source.refreshes).isEqualTo(1)
        source.cardFlow.value = card
        // 다시 연결되면 자동으로 남은 파일을 확인한다
        assertThat(source.scans).isEqualTo(1)
    }

    @Test fun moveModeChoice_survivesProcessDeath() = runTest(dispatcher) {
        val source = FakeImportFlowSource(card = card, import = ImportState.Ready(ImportFakes.summary()))
        val saved = SavedStateHandle()
        val vm = ImportViewModel(source, saved)
        backgroundScope.launch { vm.state.collect {} }
        vm.setMoveMode(false) // 기본은 '옮긴 뒤 삭제', 이번에는 '카드에 남기기'

        // 프로세스가 정리된 뒤 저장된 상태로 다시 만든다
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val vm2 = ImportViewModel(source, restored)
        backgroundScope.launch { vm2.state.collect {} }
        assertThat(vm2.state.value.moveMode).isFalse()
        vm2.start()
        assertThat(source.starts).containsExactly(false)
    }

    @Test fun destSettingsChangeWhileReady_rescans() = runTest(dispatcher) {
        val source = FakeImportFlowSource(card = card, import = ImportState.Ready(ImportFakes.summary()))
        val vm = ImportViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        assertThat(source.scans).isEqualTo(0)

        source.settingsFlow.value = source.settingsFlow.value.copy(destFolderName = "여행")
        assertThat(source.scans).isEqualTo(1)

        // 확인 중에 바뀌면 요약이 나온 뒤 한 번 더
        source.settingsFlow.value = source.settingsFlow.value.copy(monthlySubfolders = !source.settingsFlow.value.monthlySubfolders)
        assertThat(source.scans).isEqualTo(1)
        source.importFlow.value = ImportState.Ready(ImportFakes.summary())
        assertThat(source.scans).isEqualTo(2)

        // 저장 위치와 상관없는 설정은 다시 확인하지 않는다
        source.importFlow.value = ImportState.Ready(ImportFakes.summary())
        source.settingsFlow.value = source.settingsFlow.value.copy(cardNotification = !source.settingsFlow.value.cardNotification)
        assertThat(source.scans).isEqualTo(2)
    }

    @Test fun reviewCounts_reachState() = runTest(dispatcher) {
        val source = FakeImportFlowSource(card = card, import = ImportState.Finished(ImportFakes.result()))
        val vm = ImportViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        source.countsFlow.value = ImportFakes.counts
        assertThat(vm.state.value.counts).isEqualTo(ImportFakes.counts)
    }
}
