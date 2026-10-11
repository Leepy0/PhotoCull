package com.leepy0.photocull.ui.review

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 그룹 화면 → 검토 화면으로 결과(스낵바 + 실행 취소)를 넘기는 통로.
 * 그룹 화면은 정리를 마치면 바로 닫히므로, 실행 취소 스낵바는 돌아간 검토 화면에서 보여준다.
 * 액티비티 범위라 두 화면(서로 다른 내비게이션 항목)이 같은 인스턴스를 본다.
 */
class ReviewEvents : ViewModel() {
    private val _pending = MutableStateFlow<ReviewMessage?>(null)
    val pending: StateFlow<ReviewMessage?> = _pending

    fun post(message: ReviewMessage) {
        _pending.value = message
    }

    /** 받은 쪽이 꺼내 간다(한 번만 보여주기) */
    fun consume(): ReviewMessage? {
        val m = _pending.value
        _pending.value = null
        return m
    }
}

@Composable
fun rememberReviewEvents(): ReviewEvents {
    val owner: ViewModelStoreOwner = (LocalActivity.current as? ViewModelStoreOwner)
        ?: checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner" }
    return viewModel(viewModelStoreOwner = owner) { ReviewEvents() }
}
