package com.leepy0.photocull.ui.review

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
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 검토 화면(흐림·연사·영상 탭) 상태와 동작.
 * - 선택은 탭마다 따로 기억한다(목록에서 사라진 항목은 화면 상태에서 빠진다).
 * - 휴지통 이동·남기기는 확인 없이 바로 하고, 결과를 스낵바([messages])로 알려 실행 취소할 수 있게 한다.
 * - 파일 작업은 [ReviewDataSource.workScope]에서 돌아 화면을 떠나도 중간에 끊기지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModel(
    private val source: ReviewDataSource,
    initialTab: ReviewTab,
) : ViewModel() {

    constructor(container: AppContainer, initialTab: ReviewTab) : this(ContainerReviewDataSource(container), initialTab)

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
    )

    private val tab = MutableStateFlow(initialTab)
    private val blurSelection = MutableStateFlow<Set<Long>>(emptySet())
    private val videoSelection = MutableStateFlow<Set<Long>>(emptySet())
    private val busy = MutableStateFlow(false)
    private val reload = MutableStateFlow(0)

    private val _messages = Channel<ReviewMessage>(Channel.BUFFERED)

    /** 스낵바로 보여줄 결과(한 번씩 소비) */
    val messages: Flow<ReviewMessage> = _messages.receiveAsFlow()

    private val lists: Flow<Result<Lists>> = reload.flatMapLatest {
        combine(source.blurCandidates(), source.groups(), source.videos(), source.settings()) { blur, groups, videos, settings ->
            Result.success(Lists(blur, groups, videos, settings.blurSensitivity, settings.shortVideoMs))
        }.catch { e ->
            if (e is CancellationException) throw e
            emit(Result.failure(e))
        }
    }

    private val controls: Flow<Controls> =
        combine(tab, blurSelection, videoSelection, busy) { t, b, v, working -> Controls(t, b, v, working) }

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
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ReviewUiState(tab = initialTab))

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
                .onFailure { if (it is CancellationException) throw it else _messages.trySend(ReviewMessage.ActionFailed) }
        }
    }

    // ---- 선택 ----

    fun toggle(tab: ReviewTab, id: Long) {
        selectionFlow(tab)?.update { if (id in it) it - id else it + id }
    }

    fun setMarked(tab: ReviewTab, id: Long, marked: Boolean) {
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
        val visible = state.value.selection(tab)
        flow.update { current -> if (visible.containsAll(ids)) current - ids.toSet() else current + ids }
    }

    fun clearSelection(tab: ReviewTab) {
        selectionFlow(tab)?.value = emptySet()
    }

    private fun selectionFlow(tab: ReviewTab): MutableStateFlow<Set<Long>>? = when (tab) {
        ReviewTab.BLUR -> blurSelection
        ReviewTab.VIDEO -> videoSelection
        ReviewTab.BURST -> null
    }

    // ---- 동작 ----

    /** 선택한 항목을 휴지통으로(확인 없이, 실행 취소 가능) */
    fun deleteSelected(tab: ReviewTab) {
        val s = state.value
        val ids = s.selection(tab).toList()
        if (ids.isEmpty() || busy.value) return
        runWork {
            val result = source.workScope.async { source.moveToTrash(ids, emptyList()) }.await()
            val message = ReviewMessage.fromTrash(result, ids.size, s.unit(tab), SelectionRestore(tab, ids.toSet()))
            // 옮긴 것만 선택에서 뺀다(못 옮긴 것은 선택된 채로 남아 다시 시도할 수 있다)
            if (message is ReviewMessage.Trashed) selectionFlow(tab)?.update { it - message.batch.trashedIds.toSet() }
            message
        }
    }

    /** 선택이 없을 때: 탭에 남은 항목을 모두 '남기기'로 */
    fun keepRemaining(tab: ReviewTab) {
        val s = state.value
        if (busy.value) return
        val ids = when (tab) {
            ReviewTab.BURST -> s.groups.flatMap { g -> g.members.filter { it.reviewState == ReviewState.PENDING } }.map { it.id }.distinct()
            else -> s.items(tab).map { it.id }
        }
        if (ids.isEmpty()) return
        runWork {
            source.workScope.async { source.keep(ids) }.await()
            selectionFlow(tab)?.value = emptySet()
            ReviewMessage.Kept(ids, s.unit(tab))
        }
    }

    /** 모든 그룹에서 베스트만 남기기: 삭제와 남기기를 한 번에(실행 취소도 한 번에) */
    fun keepBestOfAllGroups() {
        val s = state.value
        if (busy.value) return
        val plan = s.burstPlan
        if (plan.isEmpty) return
        runWork {
            val result = source.workScope.async { source.moveToTrash(plan.deleteIds, plan.keepIds) }.await()
            ReviewMessage.fromTrash(result, plan.deleteIds.size, CountUnit.PHOTO, null)
        }
    }

    /** 스낵바 [실행 취소] */
    fun undo(message: ReviewMessage) {
        viewModelScope.launch {
            try {
                when (message) {
                    is ReviewMessage.Trashed -> source.workScope.async { source.undoTrash(message.batch) }.await()
                    is ReviewMessage.Kept -> source.workScope.async { source.unkeep(message.ids) }.await()
                    else -> return@launch
                }
                restoreSelection(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.trySend(ReviewMessage.UndoFailed)
            }
        }
    }

    /** 다른 화면(그룹 화면)에서 끝낸 작업의 결과를 이 화면 스낵바로 이어서 보여준다 */
    fun showExternal(message: ReviewMessage) {
        _messages.trySend(message)
    }

    private fun restoreSelection(message: ReviewMessage) {
        val restore = when (message) {
            is ReviewMessage.Trashed -> message.restore
            is ReviewMessage.Kept -> message.restore
            else -> null
        } ?: return
        selectionFlow(restore.tab)?.update { it + restore.ids }
    }

    private fun runWork(block: suspend () -> ReviewMessage) {
        busy.value = true
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReviewMessage.ActionFailed
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
