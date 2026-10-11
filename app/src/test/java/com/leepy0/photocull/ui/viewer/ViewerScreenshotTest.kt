package com.leepy0.photocull.ui.viewer

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leepy0.photocull.ui.PHONE
import com.leepy0.photocull.ui.capture
import com.leepy0.photocull.ui.review.FakeData
import com.leepy0.photocull.ui.review.MarkForDeletionToggle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ViewerScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun photoAtFocusUnmarked() = compose.capture("viewer_photo_phone") {
        PhotoViewerContent(
            items = FakeData.blur,
            initialIndex = 2,
            onDismiss = {},
            startAtFocus = true,
            bottomBar = { MarkForDeletionToggle(marked = false, onToggle = {}) },
        )
    }

    @Test fun photoMarkedLightTheme() = compose.capture("viewer_photo_marked_phone") {
        PhotoViewerContent(
            items = FakeData.blur,
            initialIndex = 0,
            onDismiss = {},
            startAtFocus = false,
            bottomBar = { MarkForDeletionToggle(marked = true, onToggle = {}) },
        )
    }

    @Test fun photoMarkedDarkTheme() = compose.capture("viewer_photo_marked_phone_dark", dark = true) {
        PhotoViewerContent(
            items = FakeData.blur,
            initialIndex = 0,
            onDismiss = {},
            startAtFocus = true,
            bottomBar = { MarkForDeletionToggle(marked = true, onToggle = {}) },
        )
    }

    @Test fun video() = compose.capture("viewer_video_phone") {
        PhotoViewerContent(
            items = FakeData.videos,
            initialIndex = 0,
            onDismiss = {},
            startAtFocus = false,
            bottomBar = { MarkForDeletionToggle(marked = false, onToggle = {}) },
        )
    }

    @Test fun noBottomBar() = compose.capture("viewer_no_bottom_phone") {
        PhotoViewerContent(items = FakeData.blur, initialIndex = 5, onDismiss = {}, startAtFocus = false)
    }
}
