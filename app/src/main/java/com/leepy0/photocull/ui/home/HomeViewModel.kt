package com.leepy0.photocull.ui.home

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leepy0.photocull.AppContainer
import com.leepy0.photocull.data.db.ImportSessionEntity
import com.leepy0.photocull.data.review.ReviewCounts
import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.storage.CardState
import com.leepy0.photocull.ui.components.UiState
import com.leepy0.photocull.ui.importer.AutoScanPolicy
import com.leepy0.photocull.ui.importer.ContainerImportFlowSource
import com.leepy0.photocull.ui.importer.ImportFlowSource
import com.leepy0.photocull.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 홈이 쓰는 데이터: 카드·가져오기 흐름 + 마지막 가져오기 기록 + 업데이트 상태 */
interface HomeDataSource : ImportFlowSource {
    val updateState: StateFlow<UpdateState>
    fun lastImport(): Flow<ImportSessionEntity?>
}

class ContainerHomeDataSource(private val container: AppContainer) :
    HomeDataSource, ImportFlowSource by ContainerImportFlowSource(container) {
    override val updateState: StateFlow<UpdateState> get() = container.update.state
    override fun lastImport(): Flow<ImportSessionEntity?> = container.db.sessionDao().observeLatestFinished()
}

/**
 * 홈 상태. 네트워크는 쓰지 않는다(업데이트 확인은 업데이트 모듈이 따로 한다).
 * 카드가 연결되면 한 번 카드를 확인(scan)해 "새 파일 n개"를 미리 보여준다([AutoScanPolicy]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val source: HomeDataSource) : ViewModel() {

    constructor(container: AppContainer) : this(ContainerHomeDataSource(container))

    private val policy = AutoScanPolicy()
    private val reload = MutableStateFlow(0)

    private val review: Flow<UiState<ReviewCounts>> = reload.flatMapLatest {
        source.reviewCounts()
            .map<ReviewCounts, UiState<ReviewCounts>> { UiState.Content(it) }
            .onStart { emit(UiState.Loading) }
            .catch { e ->
                if (e is CancellationException) throw e
                emit(UiState.Error(title = ""))
            }
    }

    /** (불러왔는지, 기록). 불러오기 전에 '처음 사용'으로 보이지 않게 구분한다. */
    private val lastImport: Flow<Pair<Boolean, LastImport?>> = source.lastImport()
        .map<ImportSessionEntity?, Pair<Boolean, LastImport?>> { true to it?.let(LastImport::from) }
        .onStart { emit(false to null) }
        .catch { e -> if (e is CancellationException) throw e else emit(true to null) }

    private val updateAvailable: Flow<Boolean> = source.updateState.map {
        it is UpdateState.Available || it is UpdateState.ReadyToInstall
    }

    val state: StateFlow<HomeUiState> =
        combine(
            combine(source.cardState, source.importState, ::Pair),
            review,
            source.analysis,
            lastImport,
            updateAvailable,
        ) { (card, import), r, analysis, (lastLoaded, last), update ->
            HomeUiState(
                card = card,
                import = import,
                review = r,
                analysis = analysis,
                lastImport = last,
                lastImportLoaded = lastLoaded,
                updateAvailable = update,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            HomeUiState(card = source.cardState.value, import = source.importState.value),
        )

    init {
        viewModelScope.launch {
            combine(source.cardState, source.importState, ::Pair).collect { (card, import) ->
                if (policy.shouldScan(card, import)) source.scan()
            }
        }
    }

    fun retryReview() = reload.update { it + 1 }

    /** 카드 다시 확인(오류 카드의 [다시 확인]) */
    fun rescan() {
        val import = source.importState.value
        if (import is ImportState.Running || import is ImportState.Scanning) return
        if (source.cardState.value is CardState.Ready) source.scan() else source.refreshCard()
    }

    /** 방금 끝난 결과·오류 카드를 닫는다(카드가 연결돼 있으면 곧 다시 확인한다) */
    fun dismissResult() {
        when (source.importState.value) {
            is ImportState.Finished, is ImportState.Failed -> source.reset()
            else -> Unit
        }
    }

    fun refreshCard() = source.refreshCard()

    fun folderAccessIntent(volumeId: String): Intent? = source.createAccessIntent(volumeId)

    fun onFolderAccessGranted(volumeId: String, treeUri: Uri) = source.onAccessGranted(volumeId, treeUri)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
