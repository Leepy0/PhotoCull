package com.leepy0.photocull.ui.home

import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.data.db.ImportSessionEntity
import com.leepy0.photocull.data.db.SessionState
import com.leepy0.photocull.data.review.ReviewCounts
import com.leepy0.photocull.importer.ImportError
import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.storage.CardState
import com.leepy0.photocull.ui.components.UiState
import com.leepy0.photocull.ui.importer.FakeImportFlowSource
import com.leepy0.photocull.ui.importer.ImportFakes
import com.leepy0.photocull.update.UpdateInfo
import com.leepy0.photocull.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

private class FakeHomeDataSource(
    card: CardState = CardState.NotConnected,
    import: ImportState = ImportState.Idle,
) : FakeImportFlowSource(card, import), HomeDataSource {
    val updateFlow = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val sessionFlow = MutableStateFlow<ImportSessionEntity?>(null)
    var failCounts = false

    override val updateState: StateFlow<UpdateState> get() = updateFlow
    override fun lastImport(): Flow<ImportSessionEntity?> = sessionFlow
    override fun reviewCounts(): Flow<ReviewCounts> =
        if (failCounts) flow { throw IllegalStateException("db closed") } else countsFlow
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val card = CardState.Ready(ImportFakes.volume)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun cardConnected_scansAndShowsSummary() = runTest(dispatcher) {
        val source = FakeHomeDataSource()
        source.scanResult = ImportState.Ready(ImportFakes.summary())
        val vm = HomeViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        assertThat(vm.state.value.status).isEqualTo(HomeCardStatus.NotConnected())
        source.cardFlow.value = card
        assertThat(source.scans).isEqualTo(1)
        assertThat(vm.state.value.status).isEqualTo(HomeCardStatus.CardReady(ImportFakes.summary()))
        assertThat(vm.state.value.primary).isEqualTo(HomePrimary.Import(300, ImportFakes.summary().newBytes))
    }

    @Test fun reviewCountsAndHistory_reachState() = runTest(dispatcher) {
        val source = FakeHomeDataSource()
        val vm = HomeViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        assertThat(vm.state.value.lastImportLoaded).isTrue()
        assertThat(vm.state.value.lastImport).isNull()
        source.countsFlow.value = ImportFakes.counts
        source.sessionFlow.value = ImportSessionEntity(
            id = 3, volumeId = "v", volumeLabel = "EOS", startedAt = 1, finishedAt = 2, moveMode = false,
            state = SessionState.COMPLETED, successCount = 40,
        )
        val s = vm.state.value
        assertThat(s.review).isEqualTo(UiState.Content(ImportFakes.counts))
        assertThat(s.lastImport?.savedCount).isEqualTo(40)
        assertThat(s.lastImport?.moveMode).isFalse()
    }

    @Test fun reviewError_isShownAndRetryable() = runTest(dispatcher) {
        val source = FakeHomeDataSource().apply { failCounts = true }
        val vm = HomeViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        assertThat(vm.state.value.review).isInstanceOf(UiState.Error::class.java)
        // 카드 상태는 오류와 상관없이 계속 갱신된다
        source.cardFlow.value = CardState.NoPermission
        assertThat(vm.state.value.status).isEqualTo(HomeCardStatus.NoPermission)
        source.failCounts = false
        vm.retryReview()
        assertThat(vm.state.value.review).isEqualTo(UiState.Content(ReviewCounts()))
    }

    @Test fun updateChip_onlyWhenSomethingToInstall() = runTest(dispatcher) {
        val source = FakeHomeDataSource()
        val vm = HomeViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        val info = UpdateInfo("0.2.0", 200, "u", null, null, "", "p", null)
        assertThat(vm.state.value.updateAvailable).isFalse()
        source.updateFlow.value = UpdateState.Available(info)
        assertThat(vm.state.value.updateAvailable).isTrue()
        source.updateFlow.value = UpdateState.Downloading(info, 0.5f)
        assertThat(vm.state.value.updateAvailable).isFalse()
        source.updateFlow.value = UpdateState.ReadyToInstall(info)
        assertThat(vm.state.value.updateAvailable).isTrue()
    }

    @Test fun dismissResult_resetsAndRescansConnectedCard() = runTest(dispatcher) {
        val source = FakeHomeDataSource(card = card, import = ImportState.Finished(ImportFakes.result()))
        val vm = HomeViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        assertThat(source.scans).isEqualTo(0)
        vm.dismissResult()
        assertThat(source.resets).isEqualTo(1)
        assertThat(source.scans).isEqualTo(1)
    }

    @Test fun rescan_afterFailure() = runTest(dispatcher) {
        val source = FakeHomeDataSource(card = card, import = ImportState.Failed(ImportError.Unexpected("x")))
        val vm = HomeViewModel(source)
        // 오류 상태로 새로 연결된 카드는 한 번 자동으로 확인한다
        assertThat(source.scans).isEqualTo(1)
        source.importFlow.value = ImportState.Failed(ImportError.Unexpected("x"))
        assertThat(source.scans).isEqualTo(1)
        vm.rescan()
        assertThat(source.scans).isEqualTo(2)
    }

    @Test fun rescan_ignoredWhileImporting() = runTest(dispatcher) {
        val source = FakeHomeDataSource(card = card, import = ImportState.Running(ImportFakes.progress()))
        val vm = HomeViewModel(source)
        vm.rescan()
        vm.dismissResult()
        assertThat(source.scans).isEqualTo(0)
        assertThat(source.resets).isEqualTo(0)
    }
}
