package com.leepy0.photocull.ui.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leepy0.photocull.AppContainer
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.review.GroupCard
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.data.settings.BlurSensitivity
import com.leepy0.photocull.ui.nav.ReviewTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 검토 화면(흐림·연사·영상 탭) 상태와 동작.
 * - 선택은 탭마다 따로 기억하고, 목록에서 사라진 항목은 선택에서도 지운다(민감도를 바꿨다 되돌려도 다시 선택돼 나오지 않게).
 * - 지금 탭·선택은 [SavedStateHandle]에 저장해 하단 탭을 오가거나 프로세스가 다시 떠도 이어진다.
 * - 휴지통 이동·유지는 확인 없이 바로 하고, 결과를 [sink](스낵바 + 실행 취소)로 알린다.
 * - 파일 작업은 [ReviewDataSource.workScope]에서 돌아 화면을 떠나도 중간에 끊기지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModel(
    private val source: ReviewDataSource,
    initialTab: ReviewTab,
    private val handle: SavedStateHandle = SavedStateHandle(),
    private val sink: ReviewMessageSink = ReviewMessageSink {},
) : ViewModel() {

    constructor(container: AppContainer, initialTab: ReviewTab, handle: SavedStateHandle, sink: ReviewMessageSink) :
        this(ContainerReviewDataSource(container), initialTab, handle, sink)

    private data class Lists(
        val blur: List<MediaCard>,
        val groups: List<GroupCard>,
        val videos: List<MediaCard>,
        val sensitivity: BlurSensitivity,
        val shortVideoMs: Long,
    )

    private data class Controls(
        val tab: ReviewTab,
        val blurSelection: Set<Long>,
        val videoSelection: Set<Long>,
        val busy: Boolean,
        val processed: Set<ReviewTab>,
    )

    private val tab = MutableStateFlow(
        handle.get<String>(KEY_TAB)?.let { name -> ReviewTab.entries.firstOrNull { it.name == name } } ?: initialTab,
    )
    private val blurSelection = MutableStateFlow(handle.get<LongArray>(KEY_BLUR)?.toCollection(LinkedHashSet()) ?: emptySet())
    private val videoSelection = MutableStateFlow(handle.get<LongArray>(KEY_VIDEO)?.toCollection(LinkedHashSet()) ?: emptySet())
    private val processed = MutableStateFlow(
        handle.get<Array<String>>(KEY_PROCESSED).orEmpty().mapNotNull { n -> ReviewTab.entries.firstOrNull { it.name == n } }.toSet(),
    )
    private val busy = MutableStateFlow(false)
    private val reload = MutableStateFlow(0)

    /** 실행 취소로 되살릴 선택. 되돌린 항목이 목록에 다시 나타나면 선택에 넣는다(목록 갱신이 늦을 수 있어서). */
    private val pendingRestore = mutableMapOf<ReviewTab, Set<Long>>()

    private val lists: Flow<Result<Lists>> = reload.flatMapLatest {
        combine(source.blurCandidates(), source.groups(), source.videos(), source.settings()) { blur, groups, videos, settings ->
            Result.success(Lists(blur, groups, videos, settings.blurSensitivity, settings.shortVideoMs))
        }.catch { e ->
            if (e is CancellationException) throw e
            emit(Result.failure(e))
        }
    }.onEach { result -> result.getOrNull()?.let(::syncSelection) }

    private val controls: Flow<Controls> =
        combine(tab, blurSelection, videoSelection, busy, processed) { t, b, v, working, done -> Controls(t, b, v, working, done) }

    val state: StateFlow<ReviewUiState> =
        combine(lists, controls, source.analysis) { result, c, analysis ->
            val data = result.getOrNull()
            if (data == null) {
                ReviewUiState(phase = LoadPhase.ERROR, tab = c.tab, analysis = analysis)
            } else {
                val blurIds = data.blur.mapTo(HashSet()) { it.id }
                val videoIds = data.videos.mapTo(HashSet()) { it.id }
                ReviewUiState(
                    phase = LoadPhase.READY,
                    tab = c.tab,
                    blur = data.blur,
                    groups = data.groups,
                    videos = data.videos,
                    sensitivity = data.sensitivity,
                    shortVideoMs = data.shortVideoMs,
                    blurSelection = c.blurSelection.filterTo(LinkedHashSet()) { it in blurIds },
                    videoSelection = c.videoSelection.filterTo(LinkedHashSet()) { it in videoIds },
                    analysis = analysis,
                    busy = c.busy,
                    processedTabs = c.processed,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ReviewUiState(tab = tab.value))

    init {
        // 화면 상태를 저장(하단 탭 전환·프로세스 재시작 후 복원)
        viewModelScope.launch { tab.drop(1).collect { handle[KEY_TAB] = it.name } }
        viewModelScope.launch { blurSelection.drop(1).collect { handle[KEY_BLUR] = it.toLongArray() } }
        viewModelScope.launch { videoSelection.drop(1).collect { handle[KEY_VIDEO] = it.toLongArray() } }
        viewModelScope.launch { processed.drop(1).collect { handle[KEY_PROCESSED] = it.map { t -> t.name }.toTypedArray() } }
    }

    fun selectTab(value: ReviewTab) {
        tab.value = value
    }

    fun retry() {
        reload.update { it + 1 }
    }

    fun setSensitivity(value: BlurSensitivity) {
        if (value == state.value.sensitivity) return
        source.workScope.launch {
            runCatching { source.setBlurSensitivity(value) }
                .onFailure { if (it is CancellationException) throw it else sink.post(ReviewMessage.ActionFailed) }
        }
    }

    // ---- 선택 ----

    fun toggle(tab: ReviewTab, id: Long) {
        pendingRestore.remove(tab)
        selectionFlow(tab)?.update { if (id in it) it - id else it + id }
    }

    fun setMarked(tab: ReviewTab, id: Long, marked: Boolean) {
        pendingRestore.remove(tab)
        selectionFlow(tab)?.update { if (marked) it + id else it - id }
    }

    /** 탭 전체 선택 ↔ 해제 */
    fun toggleAll(tab: ReviewTab) {
        val ids = state.value.items(tab).map { it.id }
        toggleGroupOfIds(tab, ids)
    }

    /** [ids]가 모두 선택돼 있으면 해제, 아니면 모두 선택(영상 섹션별 선택에 사용) */
    fun toggleGroupOfIds(tab: ReviewTab, ids: Collection<Long>) {
        val flow = selectionFlow(tab) ?: return
        if (ids.isEmpty()) return
        pendingRestore.remove(tab)
        val visible = state.value.selection(tab)
        flow.update { current -> if (visible.containsAll(ids)) current - ids.toSet() else current + ids }
    }

    fun clearSelection(tab: ReviewTab) {
        pendingRestore.remove(tab)
        selectionFlow(tab)?.value = emptySet()
    }

    private fun selectionFlow(tab: ReviewTab): MutableStateFlow<Set<Long>>? = when (tab) {
        ReviewTab.BLUR -> blurSelection
        ReviewTab.VIDEO -> videoSelection
        ReviewTab.BURST -> null
    }

    /** 목록이 바뀌면: 사라진 항목은 선택에서 지우고, 실행 취소로 돌아온 항목은 다시 선택한다 */
    private fun syncSelection(l: Lists) {
        syncSelection(ReviewTab.BLUR, l.blur.mapTo(HashSet()) { it.id })
        syncSelection(ReviewTab.VIDEO, l.videos.mapTo(HashSet()) { it.id })
    }

    private fun syncSelection(tab: ReviewTab, present: Set<Long>) {
        val flow = selectionFlow(tab) ?: return
        val back = pendingRestore[tab]?.filterTo(LinkedHashSet()) { it in present }.orEmpty()
        if (back.isNotEmpty()) {
            val rest = pendingRestore.getValue(tab) - back
            if (rest.isEmpty()) pendingRestore.remove(tab) else pendingRestore[tab] = rest
        }
        flow.update { current ->
            val kept = current.filterTo(LinkedHashSet()) { it in present }
            kept.addAll(back)
            if (kept == current) current else kept
        }
    }

    // ---- 동작 ----

    /** 선택한 항목을 휴지통으로(확인 없이, 실행 취소 가능) */
    fun deleteSelected(tab: ReviewTab) {
        val s = state.value
        val ids = s.selection(tab).toList()
        if (ids.isEmpty() || busy.value) return
        runWork(tab) {
            val result = source.moveToTrash(ids, emptyList())
            val message = ReviewMessage.fromTrash(result, s.unit(tab), SelectionRestore(tab, ids.toSet()))
            // 옮긴 것만 선택에서 뺀다(못 옮긴 것은 선택된 채로 남아 다시 시도할 수 있다)
            if (message is ReviewMessage.Trashed) selectionFlow(tab)?.update { it - message.batch.trashedIds.toSet() }
            message
        }
    }

    /** 선택이 없을 때: 탭에 남은 항목을 모두 '유지'로(삭제 없이 검토 목록에서 뺀다) */
    fun keepRemaining(tab: ReviewTab) {
        val s = state.value
        if (busy.value) return
        val ids = when (tab) {
            ReviewTab.BURST -> s.groups.flatMap { g -> g.members.filter { it.reviewState == ReviewState.PENDING } }.map { it.id }.distinct()
            else -> s.items(tab).map { it.id }
        }
        if (ids.isEmpty()) return
        runWork(tab) {
            source.keep(ids)
            selectionFlow(tab)?.value = emptySet()
            ReviewMessage.Kept(ids, s.unit(tab))
        }
    }

    /**
     * 모든 그룹에서 베스트만 남기기: 삭제와 유지를 한 번에(실행 취소도 한 번에).
     * 끝나면 그룹을 다시 묶어, 전체가 흐린 그룹에서 혼자 남은 베스트가 흐림 탭에 다시 나오게 한다.
     */
    fun keepBestOfAllGroups() {
        val s = state.value
        if (busy.value) return
        val plan = s.burstPlan
        if (plan.isEmpty) return
        runWork(ReviewTab.BURST) {
            val result = source.moveToTrash(plan.deleteIds, plan.keepIds)
            if (result.movedCount > 0) source.regroup()
            ReviewMessage.fromTrash(result, CountUnit.PHOTO, null, regroupOnUndo = true)
        }
    }

    /** 스낵바 [실행 취소]. 다른 작업 중이면 결과를 다시 띄워 나중에 누를 수 있게 한다. */
    fun undo(message: ReviewMessage) {
        if (!message.undoable) return
        if (!busy.compareAndSet(expect = false, update = true)) {
            sink.post(message)
            return
        }
        source.workScope.launch {
            try {
                source.undo(message)
                restoreSelection(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sink.post(ReviewMessage.UndoFailed)
            } finally {
                busy.value = false
            }
        }
    }

    private fun restoreSelection(message: ReviewMessage) {
        val restore = when (message) {
            is ReviewMessage.Trashed -> message.restore
            is ReviewMessage.Kept -> message.restore
            else -> null
        } ?: return
        val flow = selectionFlow(restore.tab) ?: return
        val present = state.value.items(restore.tab).mapTo(HashSet()) { it.id }
        val now = restore.ids.filterTo(LinkedHashSet()) { it in present }
        flow.update { it + now }
        val later = restore.ids - now
        if (later.isNotEmpty()) pendingRestore[restore.tab] = later
    }

    /**
     * 파일 작업과 결과 알림을 앱 범위([ReviewDataSource.workScope])에서 끝까지 한다.
     * 화면(ViewModel)이 먼저 사라져도 결과(실행 취소 스낵바)는 다른 화면에 나온다.
     */
    private fun runWork(tab: ReviewTab, block: suspend () -> ReviewMessage) {
        if (!busy.compareAndSet(expect = false, update = true)) return
        source.workScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                busy.value = false
                throw e
            } catch (e: Exception) {
                ReviewMessage.ActionFailed
            }
            if (message is ReviewMessage.Trashed || message is ReviewMessage.Kept) processed.update { it + tab }
            busy.value = false
            sink.post(message)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val KEY_TAB = "review.tab"
        const val KEY_BLUR = "review.blurSelection"
        const val KEY_VIDEO = "review.videoSelection"
        const val KEY_PROCESSED = "review.processedTabs"
    }
}
