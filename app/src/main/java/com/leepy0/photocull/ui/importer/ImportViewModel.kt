package com.leepy0.photocull.ui.importer

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leepy0.photocull.AppContainer
import com.leepy0.photocull.data.review.ReviewCounts
import com.leepy0.photocull.data.settings.AppSettings
import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.storage.CardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 가져오기 화면 상태와 동작. 실제 복사는 ImportService가 하고, 여기서는 상태를 보여주고 시작·취소만 요청한다.
 * - 카드가 연결돼 있으면 화면을 열 때 자동으로 카드를 확인한다([AutoScanPolicy]).
 * - 카드 원본 처리(옮긴 뒤 삭제 / 카드에 남기기)는 설정값이 기본이고, 이 화면에서 바꾼 값은 이번 가져오기에만 쓴다.
 *   바꾼 값은 [SavedStateHandle]에 두어 프로세스가 정리됐다 돌아와도(저장공간 설정에 다녀오는 사이 등) 유지된다.
 * - 요약을 보여주는 중 저장 폴더·월별 폴더 설정이 바뀌면 카드를 다시 확인한다(요약의 저장 위치가 낡지 않게).
 */
class ImportViewModel(
    private val source: ImportFlowSource,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    constructor(container: AppContainer, savedState: SavedStateHandle) : this(ContainerImportFlowSource(container), savedState)

    private val policy = AutoScanPolicy()
    private val moveModeOverride: StateFlow<Boolean?> = savedState.getStateFlow<Boolean?>(KEY_MOVE_MODE, null)

    /** 확인 중에 저장 위치 설정이 바뀌었으면 요약이 나온 뒤 한 번 더 확인한다 */
    private var destChangedWhileScanning = false
    private val cancelRequested = MutableStateFlow(false)

    private val defaultMoveMode: Flow<Boolean> = source.settings()
        .map { it.moveMode }
        .distinctUntilChanged()
        .catch { e -> if (e is CancellationException) throw e else emit(true) }

    /** 화면에 보이는 값 = [가져오기 시작]이 쓰는 값(구독자가 없어도 늘 최신) */
    private val moveMode: StateFlow<Boolean> = combine(moveModeOverride, defaultMoveMode) { chosen, default -> chosen ?: default }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings().moveMode)

    private val counts: Flow<ReviewCounts?> = source.reviewCounts()
        .map<ReviewCounts, ReviewCounts?> { it }
        .onStart { emit(null) }
        .catch { e -> if (e is CancellationException) throw e else emit(null) }

    val state: StateFlow<ImportUiState> =
        combine(
            combine(source.importState, source.cardState, ::Pair),
            moveMode,
            counts,
            source.analysis,
            cancelRequested,
        ) { (import, card), move, c, analysis, cancelling ->
            ImportUiState(
                import = import,
                card = card,
                moveMode = move,
                counts = c,
                analysis = analysis,
                cancelRequested = cancelling && import is ImportState.Running,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            ImportUiState(import = source.importState.value, card = source.cardState.value),
        )

    init {
        viewModelScope.launch {
            combine(source.cardState, source.importState, ::Pair).collect { (card, import) ->
                if (import !is ImportState.Running) cancelRequested.value = false
                if (policy.shouldScan(card, import)) {
                    source.scan()
                } else if (import is ImportState.Ready && destChangedWhileScanning) {
                    destChangedWhileScanning = false
                    source.scan()
                }
            }
        }
        viewModelScope.launch {
            source.settings()
                .map { it.destFolderName to it.monthlySubfolders }
                .distinctUntilChanged()
                .drop(1)
                .catch { e -> if (e is CancellationException) throw e }
                .collect {
                    when (source.importState.value) {
                        is ImportState.Ready -> source.scan()
                        ImportState.Scanning -> destChangedWhileScanning = true
                        else -> Unit
                    }
                }
        }
    }

    fun setMoveMode(move: Boolean) {
        savedState[KEY_MOVE_MODE] = move
    }

    /** [가져오기 시작]. 공간이 모자라거나 새 파일이 없으면 아무것도 하지 않는다(버튼도 그때는 막혀 있다). */
    fun start() {
        val ready = source.importState.value as? ImportState.Ready ?: return
        val summary = ready.summary
        if (!summary.enoughSpace || summary.newFileCount <= 0) return
        cancelRequested.value = false
        source.start(moveMode.value)
    }

    /** 취소는 안전하다(옮긴 파일은 폰에 남고 나머지는 카드에 그대로) → 확인 없이 바로 */
    fun cancel() {
        if (source.importState.value !is ImportState.Running) return
        cancelRequested.value = true
        source.cancel()
    }

    /** 카드 다시 확인([다시 확인], 오류 화면의 재시도) */
    fun rescan() {
        when (source.importState.value) {
            is ImportState.Running -> return
            ImportState.Scanning -> return
            else -> Unit
        }
        if (source.cardState.value is CardState.Ready) source.scan() else source.refreshCard()
    }

    fun refreshCard() = source.refreshCard()

    /** 화면 복귀: 공간 부족으로 막혀 있었으면 여유 공간을 다시 계산한다(저장공간 설정에서 정리하고 돌아온 경우) */
    fun onResume() {
        val ready = source.importState.value as? ImportState.Ready ?: return
        if (!ready.summary.enoughSpace) source.scan()
    }

    /** 결과·오류 화면을 닫고 나갈 때 */
    fun dismissResult() {
        when (source.importState.value) {
            is ImportState.Finished, is ImportState.Failed -> source.reset()
            else -> Unit
        }
    }

    fun folderAccessIntent(volumeId: String): Intent? = source.createAccessIntent(volumeId)

    fun onFolderAccessGranted(volumeId: String, treeUri: Uri) = source.onAccessGranted(volumeId, treeUri)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val KEY_MOVE_MODE = "import_move_mode"
    }
}
