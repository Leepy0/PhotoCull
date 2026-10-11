package com.leepy0.photocull.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leepy0.photocull.AppContainer
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.review.GroupCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 연사/유사 그룹 1개 정리. 기본 선택 = 베스트와 이미 남긴 사진을 뺀 나머지([GroupCard.defaultSelection]).
 * 삭제하면 선택하지 않은 사진은 '남기기'로 함께 처리하고(한 번의 휴지통 이동 = 한 번에 실행 취소),
 * 결과를 [finished]로 알려 화면이 검토 화면으로 돌아가 스낵바를 띄우게 한다.
 */
class GroupViewModel(
    private val source: ReviewDataSource,
    private val groupId: Long,
) : ViewModel() {

    constructor(container: AppContainer, groupId: Long) : this(ContainerReviewDataSource(container), groupId)

    /** null = 아직 첫 데이터 전(받으면 기본 선택으로 채움) */
    private val selection = MutableStateFlow<Set<Long>?>(null)
    private val currentId = MutableStateFlow<Long?>(null)
    private val busy = MutableStateFlow(false)

    /** 삭제/남기기를 끝내 그룹이 사라지는 중(사라짐을 '이미 정리됨'으로 보이지 않게) */
    private val done = MutableStateFlow(false)

    private var lastGroup: GroupCard? = null

    private val _finished = Channel<ReviewMessage>(Channel.BUFFERED)

    /** 그룹 정리를 마침 → 화면은 이 결과를 검토 화면에 넘기고 뒤로 간다 */
    val finished: Flow<ReviewMessage> = _finished.receiveAsFlow()

    private val _messages = Channel<ReviewMessage>(Channel.BUFFERED)

    /** 이 화면에서 보여줄 스낵바(실패 등) */
    val messages: Flow<ReviewMessage> = _messages.receiveAsFlow()

    private val group: Flow<Result<GroupCard?>> = source.group(groupId)
        .onEach { g -> if (g != null) onGroup(g) }
        .map { Result.success(it) }
        .catch { e ->
            if (e is CancellationException) throw e
            emit(Result.failure(e))
        }

    val state: StateFlow<GroupUiState> =
        combine(group, selection, currentId, busy, done) { result, sel, cur, working, isDone ->
            val g = result.getOrElse { return@combine GroupUiState.Error }
            when {
                g == null && (isDone || working) -> GroupUiState.Loading
                g == null -> GroupUiState.Missing
                else -> {
                    val memberIds = g.members.mapTo(HashSet()) { it.id }
                    GroupUiState.Content(
                        group = g,
                        selection = (sel ?: g.defaultSelection()).filterTo(LinkedHashSet()) { it in memberIds },
                        currentId = cur?.takeIf { it in memberIds } ?: g.bestId,
                        busy = working || isDone,
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GroupUiState.Loading)

    private fun onGroup(g: GroupCard) {
        lastGroup = g
        if (selection.value == null) selection.value = g.defaultSelection()
        val cur = currentId.value
        if (cur == null || g.members.none { it.id == cur }) currentId.value = g.bestId
    }

    fun show(id: Long) {
        currentId.value = id
    }

    fun toggle(id: Long) {
        selection.update { sel ->
            val base = sel ?: lastGroup?.defaultSelection() ?: emptySet()
            if (id in base) base - id else base + id
        }
    }

    fun setMarked(id: Long, marked: Boolean) {
        selection.update { sel ->
            val base = sel ?: lastGroup?.defaultSelection() ?: emptySet()
            if (marked) base + id else base - id
        }
    }

    /**
     * [itemId]를 베스트로 고정. 사용자가 선택을 손대지 않았으면(= 기본 선택) 새 베스트 기준 기본 선택으로 바꾸고,
     * 손댔으면 새 베스트만 선택에서 뺀다(베스트를 지우지 않게).
     */
    fun pinBest(itemId: Long) {
        val content = state.value as? GroupUiState.Content ?: return
        val g = content.group
        if (g.bestId == itemId || g.members.none { it.id == itemId }) return
        val untouched = content.selection == g.defaultSelection()
        selection.value = if (untouched) {
            g.members.filter { it.id != itemId && it.reviewState == ReviewState.PENDING }.mapTo(LinkedHashSet()) { it.id }
        } else {
            content.selection - itemId
        }
        viewModelScope.launch {
            try {
                source.workScope.async { source.pinBest(groupId, itemId) }.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.trySend(ReviewMessage.ActionFailed)
            }
        }
    }

    /** 선택한 사진을 휴지통으로, 나머지(판단 전인 것)는 남기기로. 실패하면 화면에 머문다. */
    fun deleteSelected() {
        val content = state.value as? GroupUiState.Content ?: return
        if (busy.value) return
        val ids = content.group.members.filter { it.id in content.selection }.map { it.id }
        if (ids.isEmpty()) return
        val keepIds = content.keepIds
        busy.value = true
        viewModelScope.launch {
            try {
                val result = source.workScope.async { source.moveToTrash(ids, keepIds) }.await()
                when (val message = ReviewMessage.fromTrash(result, ids.size, CountUnit.PHOTO, null)) {
                    is ReviewMessage.Trashed -> {
                        done.value = true
                        _finished.send(message)
                    }
                    else -> _messages.send(message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.trySend(ReviewMessage.ActionFailed)
            } finally {
                busy.value = false
            }
        }
    }

    /** 선택 없음: 모두 남기고 그룹 정리를 마친다 */
    fun keepAll() {
        val content = state.value as? GroupUiState.Content ?: return
        if (busy.value) return
        val ids = content.pendingIds
        busy.value = true
        viewModelScope.launch {
            try {
                if (ids.isNotEmpty()) source.workScope.async { source.keep(ids) }.await()
                done.value = true
                _finished.send(ReviewMessage.Kept(ids, CountUnit.PHOTO))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.trySend(ReviewMessage.ActionFailed)
            } finally {
                busy.value = false
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
