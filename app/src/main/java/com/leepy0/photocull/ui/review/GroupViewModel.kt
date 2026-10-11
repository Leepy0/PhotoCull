package com.leepy0.photocull.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leepy0.photocull.AppContainer
import com.leepy0.photocull.data.review.GroupCard
import kotlinx.coroutines.CancellationException
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
 * 연사/유사 그룹 1개 정리.
 * - 선택: 사용자가 손대기 전에는 늘 [GroupCard.defaultSelection](베스트와 이미 유지한 사진을 뺀 나머지)을 따른다
 *   (분석이 그룹에 사진을 더하거나 베스트가 바뀌어도 새 기준으로). 손댄 뒤에는 그 선택을 지금 멤버로 좁히고,
 *   지금 베스트는 언제나 뺀다(베스트는 남긴다).
 * - 삭제하면 선택하지 않은 사진은 '유지'로 함께 처리하고(한 번의 휴지통 이동 = 한 번에 실행 취소),
 *   결과는 [sink](검토 화면과 함께 쓰는 스낵바)로 알린 뒤 [finished]로 화면을 닫게 한다.
 *   파일 작업과 결과 알림은 앱 범위에서 끝까지 하므로, 도중에 뒤로 가도 실행 취소 스낵바가 사라지지 않는다.
 */
class GroupViewModel(
    private val source: ReviewDataSource,
    private val groupId: Long,
    private val sink: ReviewMessageSink = ReviewMessageSink {},
) : ViewModel() {

    constructor(container: AppContainer, groupId: Long, sink: ReviewMessageSink) :
        this(ContainerReviewDataSource(container), groupId, sink)

    /** null = 사용자가 아직 손대지 않음(기본 선택을 따름) */
    private val userSelection = MutableStateFlow<Set<Long>?>(null)
    private val currentId = MutableStateFlow<Long?>(null)
    private val busy = MutableStateFlow(false)

    /** [pinBest]를 저장하는 동안 미리 반영할 새 베스트(목록이 따라오면 지운다) */
    private val pendingBest = MutableStateFlow<Long?>(null)

    /** 삭제/유지를 끝내 그룹이 사라지는 중(사라짐을 '이미 정리됨'으로 보이지 않게) */
    private val done = MutableStateFlow(false)

    private val _finished = Channel<ReviewMessage>(Channel.BUFFERED)

    /** 그룹 정리를 마침 → 화면은 뒤로 간다(결과 스낵바는 [sink]가 이미 받았다) */
    val finished: Flow<ReviewMessage> = _finished.receiveAsFlow()

    private val group: Flow<Result<GroupCard?>> = source.group(groupId)
        .onEach { g -> if (g != null && pendingBest.value == g.bestId) pendingBest.value = null }
        .map { Result.success(it) }
        .catch { e ->
            if (e is CancellationException) throw e
            emit(Result.failure(e))
        }

    private val selectionInputs = combine(userSelection, pendingBest) { sel, best -> sel to best }

    val state: StateFlow<GroupUiState> =
        combine(group, selectionInputs, currentId, busy, done) { result, (sel, pinned), cur, working, isDone ->
            val g = result.getOrElse { return@combine GroupUiState.Error }
            when {
                g == null && (isDone || working) -> GroupUiState.Loading
                g == null -> GroupUiState.Missing
                else -> {
                    val shown = if (pinned != null && g.members.any { it.id == pinned }) g.copy(bestId = pinned, bestPinned = true) else g
                    val memberIds = shown.members.mapTo(HashSet()) { it.id }
                    GroupUiState.Content(
                        group = shown,
                        selection = effectiveSelection(shown, sel),
                        currentId = cur?.takeIf { it in memberIds } ?: shown.bestId,
                        busy = working || isDone,
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GroupUiState.Loading)

    private val content: GroupUiState.Content? get() = state.value as? GroupUiState.Content

    fun show(id: Long) {
        currentId.value = id
    }

    fun toggle(id: Long) {
        val c = content ?: return
        setMarked(id, id !in c.selection)
    }

    /** 베스트는 표시할 수 없다(먼저 다른 사진을 베스트로 지정) */
    fun setMarked(id: Long, marked: Boolean) {
        val c = content ?: return
        if (id == c.group.bestId || c.group.members.none { it.id == id }) return
        userSelection.value = if (marked) c.selection + id else c.selection - id
    }

    /**
     * [itemId]를 베스트로 고정. 선택을 손대지 않았으면 새 베스트 기준 기본 선택이 되고,
     * 손댔으면 새 베스트만 선택에서 빠진다(이전 베스트는 사용자가 고르지 않았으므로 그대로 미선택).
     */
    fun pinBest(itemId: Long) {
        val c = content ?: return
        val g = c.group
        if (g.bestId == itemId || g.members.none { it.id == itemId }) return
        pendingBest.value = itemId
        viewModelScope.launch {
            try {
                source.pinBest(groupId, itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingBest.update { if (it == itemId) null else it }
                sink.post(ReviewMessage.ActionFailed)
            }
        }
    }

    /** 선택한 사진을 휴지통으로, 나머지(판단 전인 것)는 유지로. 실패하면 화면에 머문다. */
    fun deleteSelected() {
        val c = content ?: return
        val ids = c.group.members.filter { it.id in c.selection }.map { it.id }
        if (ids.isEmpty()) return
        val keepIds = c.keepIds
        runWork {
            ReviewMessage.fromTrash(source.moveToTrash(ids, keepIds), CountUnit.PHOTO, null)
        }
    }

    /** 선택 없음: 모두 유지하고 그룹 정리를 마친다 */
    fun keepAll() {
        val c = content ?: return
        val ids = c.pendingIds
        runWork {
            if (ids.isNotEmpty()) source.keep(ids)
            ReviewMessage.Kept(ids, CountUnit.PHOTO)
        }
    }

    /** 스낵바 [실행 취소](검토 화면에서 정리한 결과를 이 화면에서 누른 경우 등) */
    fun undo(message: ReviewMessage) {
        if (!message.undoable) return
        if (!busy.compareAndSet(expect = false, update = true)) {
            sink.post(message)
            return
        }
        source.workScope.launch {
            try {
                source.undo(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sink.post(ReviewMessage.UndoFailed)
            } finally {
                busy.value = false
            }
        }
    }

    /** 앱 범위에서 작업 → 결과를 [sink]로. 성공이면 화면을 닫게 [finished]도 보낸다. */
    private fun runWork(block: suspend () -> ReviewMessage) {
        if (done.value || !busy.compareAndSet(expect = false, update = true)) return
        source.workScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                busy.value = false
                throw e
            } catch (e: Exception) {
                ReviewMessage.ActionFailed
            }
            val success = message is ReviewMessage.Trashed || message is ReviewMessage.Kept
            if (success) done.value = true
            busy.value = false
            sink.post(message)
            if (success) _finished.trySend(message)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** 손대기 전 = 기본 선택. 손댄 뒤 = 그 선택 중 지금 멤버인 것만, 베스트 제외 */
        fun effectiveSelection(g: GroupCard, user: Set<Long>?): Set<Long> {
            if (user == null) return g.defaultSelection()
            val members = g.members.mapTo(HashSet()) { it.id }
            return user.filterTo(LinkedHashSet()) { it in members && it != g.bestId }
        }
    }
}
