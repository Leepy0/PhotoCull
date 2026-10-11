package com.leepy0.photocull.ui.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leepy0.photocull.ui.capture
import com.leepy0.photocull.ui.importer.KO_PHONE
import com.leepy0.photocull.ui.importer.KO_UNFOLDED
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = KO_PHONE)
class OnboardingScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nothingGranted() = compose.capture("onboarding_nothing_phone") {
        OnboardingContent(OnboardingUiState(), OnboardingActions())
    }

    @Test fun nothingGrantedDark() = compose.capture("onboarding_nothing_phone_dark", dark = true) {
        OnboardingContent(OnboardingUiState(), OnboardingActions())
    }

    @Test fun filesOnlyNotificationsBlocked() = compose.capture("onboarding_files_only_phone") {
        OnboardingContent(OnboardingUiState(filesGranted = true, notificationsBlocked = true), OnboardingActions())
    }

    @Test fun allGranted() = compose.capture("onboarding_all_phone") {
        OnboardingContent(OnboardingUiState(filesGranted = true, notificationsGranted = true), OnboardingActions())
    }

    @Config(qualifiers = KO_UNFOLDED)
    @Test fun nothingGrantedUnfolded() = compose.capture("onboarding_nothing_unfolded") {
        OnboardingContent(OnboardingUiState(), OnboardingActions())
    }
}
