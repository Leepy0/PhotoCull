package com.leepy0.photocull.ui.review

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leepy0.photocull.ui.nav.ReviewTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** 작업 결과(스낵바로 알릴 것)를 받는 곳. 화면 밖(앱 범위 작업)에서도 부를 수 있다. */
fun interface ReviewMessageSink {
    fun post(message: ReviewMessage)
}

/** 게시된 결과 1건. [seq]로 같은 결과를 두 번 처리하지 않는다. */
data class PostedMessage(val seq: Long, val message: ReviewMessage)

/**
 * 검토·그룹 화면이 함께 쓰는 결과 알림 통로(액티비티 범위라 두 화면이 같은 인스턴스를 본다).
 * - [undo]: 마지막으로 되돌릴 수 있는 작업. 사용자가 [실행 취소]/닫기를 누르거나 새 삭제·유지가 생길 때까지 남아,
 *   그룹 화면 ↔ 검토 화면을 오가도 지금 보이는 화면의 스낵바에 다시 나온다.
 * - [notice]: 실패 등 짧게 알리고 끝나는 결과. 보이는 동안 [undo]보다 먼저 나온다.
 * - [tabRequest]: 홈·알림에서 "연사 탭으로" 같은 요청. 이미 있는 검토 화면이 한 번 받아 탭만 바꾼다.
 */
class ReviewEvents : ViewModel(), ReviewMessageSink {
    private val seq = AtomicLong()

    private val _undo = MutableStateFlow<PostedMessage?>(null)
    val undo: StateFlow<PostedMessage?> = _undo.asStateFlow()

    private val _notice = MutableStateFlow<PostedMessage?>(null)
    val notice: StateFlow<PostedMessage?> = _notice.asStateFlow()

    private val _tabRequest = MutableStateFlow<ReviewTab?>(null)
    val tabRequest: StateFlow<ReviewTab?> = _tabRequest.asStateFlow()

    override fun post(message: ReviewMessage) {
        val posted = PostedMessage(seq.incrementAndGet(), message)
        if (message.undoable) {
            _undo.value = posted
            // 새 결과가 나왔으니 이전 짧은 알림은 뺀다
            _notice.value = null
        } else {
            _notice.value = posted
        }
    }

    /** 결과를 처리함(실행 취소·닫기·시간 지남). 그 사이 새 결과가 왔으면 그대로 둔다. */
    fun clear(seq: Long) {
        _undo.update { if (it?.seq == seq) null else it }
        _notice.update { if (it?.seq == seq) null else it }
    }

    fun requestTab(tab: ReviewTab) {
        _tabRequest.value = tab
    }

    /** 받은 쪽이 꺼내 간다(한 번만 적용) */
    fun consumeTabRequest(): ReviewTab? = _tabRequest.getAndUpdate { null }
}

@Composable
fun rememberReviewEvents(): ReviewEvents {
    val owner: ViewModelStoreOwner = (LocalActivity.current as? ViewModelStoreOwner)
        ?: checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner" }
    return viewModel(viewModelStoreOwner = owner) { ReviewEvents() }
}
