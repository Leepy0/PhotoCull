package com.leepy0.photocull.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leepy0.photocull.AppContainer
import com.leepy0.photocull.data.review.MediaCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 휴지통 화면 상태와 동작.
 * - 복원은 확인 없이 바로 하고 결과를 스낵바로 알린다(복원은 데이터를 잃지 않는다).
 * - 영구 삭제(선택 / 비우기)는 되돌릴 수 없어서 확인 대화상자([TrashUiState.confirm])를 거친다.
 *   확인하면 대화상자에 보였던 항목만 지운다(그사이 휴지통에 새로 들어온 것은 남는다).
 * - 파일 작업은 [TrashDataSource.workScope]에서 돌아 화면을 떠나도 중간에 끊기지 않는다.
 * - 화면을 열 때 보관 기간이 지난 항목을 정리한다(앱을 오래 켜 둬도 기간이 지난 항목이 남지 않게).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModel(private val source: TrashDataSource) : ViewModel() {

    constructor(container: AppContainer) : this(ContainerTrashDataSource(container))

    private data class Controls(val selection: Set<Long>, val busy: Boolean, val confirm: DeleteConfirm?)

    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    private val busy = MutableStateFlow(false)
    private val confirm = MutableStateFlow<DeleteConfirm?>(null)
    private val reload = MutableStateFlow(0)

    private val _messages = Channel<TrashMessage>(Channel.BUFFERED)

    /** 스낵바로 보여줄 결과(한 번씩 소비) */
    val messages: Flow<TrashMessage> = _messages.receiveAsFlow()

    private val data: Flow<Result<Pair<List<MediaCard>, Int>>> = reload.flatMapLatest {
        combine(source.trashed(), source.retentionDays()) { items, days -> Result.success(items to days) }
            .catch { e ->
                if (e is CancellationException) throw e
                emit(Result.failure(e))
            }
    }

    private val controls: Flow<Controls> = combine(selection, busy, confirm) { s, b, c -> Controls(s, b, c) }

    val state: StateFlow<TrashUiState> = combine(data, controls) { result, c ->
        val loaded = result.getOrNull()
        if (loaded == null) {
            TrashUiState(phase = TrashPhase.ERROR, busy = c.busy)
        } else {
            val (items, days) = loaded
            val visible = items.mapTo(HashSet()) { it.id }
            TrashUiState(
                phase = TrashPhase.READY,
                items = items,
                selection = c.selection.filterTo(LinkedHashSet()) { it in visible },
                retentionDays = days,
                now = source.now(),
                busy = c.busy,
                confirm = c.confirm,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TrashUiState())

    init {
        source.workScope.launch {
            try {
                source.purgeExpired()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 다음에 화면을 열 때나 앱을 시작할 때 다시 정리한다
            }
        }
    }

    fun retry() {
        reload.update { it + 1 }
    }

    // ---- 선택 ----

    fun toggle(id: Long) {
        selection.update { if (id in it) it - id else it + id }
    }

    /** 전체 선택 ↔ 해제 */
    fun toggleAll() {
        val s = state.value
        selection.value = if (s.allSelected) emptySet() else s.items.mapTo(LinkedHashSet()) { it.id }
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    // ---- 복원 ----

    fun restoreSelected() {
        restore(state.value.selection.toList())
    }

    /** 뷰어의 [복원] */
    fun restoreOne(id: Long) {
        restore(listOf(id))
    }

    private fun restore(ids: List<Long>) {
        if (ids.isEmpty() || busy.value) return
        runWork(onError = TrashMessage.RestoreFailed) {
            val report = source.workScope.async { source.restore(ids) }.await()
            if (report.restored > 0 || report.gone > 0) selection.update { it - ids.toSet() }
            TrashMessage.restored(ids.size, report)
        }
    }

    // ---- 영구 삭제 ----

    fun askDeleteSelected() {
        val items = state.value.selectedItems
        if (items.isEmpty()) return
        confirm.value = DeleteConfirm(items.map { it.id }, items.size, items.sumOf { it.bytes }, items.any { it.hasRaw })
    }

    /** 휴지통 비우기: 지금 보이는 항목 목록을 대화상자에 담는다(확인하면 이 목록만 지운다) */
    fun askEmptyTrash() {
        val items = state.value.items
        if (items.isEmpty()) return
        confirm.value = DeleteConfirm(items.map { it.id }, items.size, items.sumOf { it.bytes }, items.any { it.hasRaw })
    }

    fun dismissConfirm() {
        confirm.value = null
    }

    fun confirmDelete() {
        val request = confirm.value ?: return
        confirm.value = null
        if (busy.value) return
        runWork(onError = TrashMessage.DeleteFailed) {
            val deleted = source.workScope.async { source.deleteForever(request.ids) }.await()
            if (deleted > 0) selection.update { it - request.ids.toSet() }
            // 일부만 지웠으면 확보 용량을 어림하지 않는다(부분 실패 문구에는 용량이 없다)
            val bytes = if (deleted >= request.count) request.bytes else 0L
            TrashMessage.deleted(request.count, deleted, bytes)
        }
    }

    /** [block]이 예외로 끝나면 [onError]를 알린다(예외 원문은 화면에 보이지 않는다) */
    private fun runWork(onError: TrashMessage, block: suspend () -> TrashMessage) {
        busy.value = true
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError
            } finally {
                busy.value = false
            }
            _messages.trySend(message)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
