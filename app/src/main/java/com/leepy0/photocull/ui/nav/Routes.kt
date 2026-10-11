package com.leepy0.photocull.ui.nav

import kotlinx.serialization.Serializable

enum class ReviewTab { BLUR, BURST, VIDEO }

@Serializable object HomeRoute
@Serializable data class ReviewRoute(val tab: String = ReviewTab.BLUR.name)
@Serializable object TrashRoute
@Serializable object SettingsRoute
@Serializable object ImportRoute
@Serializable data class GroupRoute(val groupId: Long)
@Serializable object OnboardingRoute
