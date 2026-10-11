package com.leepy0.photocull.ui.components

/** 모든 화면은 Loading / Empty / Error / Content 4상태로 모델링한다. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Empty(val reason: String? = null) : UiState<Nothing>
    /** title = 무엇이, cause = 왜, fix = 어떻게 해결 */
    data class Error(val title: String, val cause: String? = null, val fix: String? = null) : UiState<Nothing>
    data class Content<T>(val data: T) : UiState<T>
}
