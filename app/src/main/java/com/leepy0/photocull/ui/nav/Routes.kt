package com.leepy0.photocull.ui.nav

import kotlinx.serialization.Serializable

enum class ReviewTab { BLUR, BURST, VIDEO }

@Serializable object HomeRoute

/** [tab]은 검토 화면을 처음 만들 때만 쓴다(이미 있는 화면의 탭은 ReviewEvents.requestTab으로 바꾼다) */
@Serializable data class ReviewRoute(val tab: String = ReviewTab.BLUR.name)
@Serializable object TrashRoute
@Serializable object SettingsRoute
@Serializable object ImportRoute
@Serializable data class GroupRoute(val groupId: Long)

/** [firstRun]: 앱 시작 때 필수 권한이 없어 올린 시작 화면(뒤로 가면 앱을 닫는다) */
@Serializable data class OnboardingRoute(val firstRun: Boolean = false)
