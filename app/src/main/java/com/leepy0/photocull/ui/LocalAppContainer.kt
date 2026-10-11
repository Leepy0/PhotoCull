package com.leepy0.photocull.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.leepy0.photocull.AppContainer

/** 화면에서 앱 전역 객체에 접근. ViewModel 생성 시 사용: viewModel { XViewModel(container) } */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
