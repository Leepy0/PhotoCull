package com.leepy0.photocull.ui.trash

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.leepy0.photocull.ui.PHONE
import com.leepy0.photocull.ui.ScreenshotFrame
import com.leepy0.photocull.ui.UNFOLDED
import com.leepy0.photocull.ui.capture
import com.leepy0.photocull.ui.viewer.PhotoViewerContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class TrashScreenshotTest {
    @get:Rule val compose = createComposeRule()

    /** 대화상자·팝업처럼 별도 창이 있는 화면은 창 전체를 찍는다 */
    private fun captureScreen(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { ScreenshotFrame(dark, content) }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun nothingSelected() = compose.capture("trash_phone") {
        TrashContent(TrashFakeData.ready(), TrashActions())
    }

    @Test fun withSelection() = compose.capture("trash_selected_phone") {
        TrashContent(TrashFakeData.ready(selection = setOf(2, 4, 5)), TrashActions())
    }

    @Test fun withSelectionDark() = compose.capture("trash_selected_phone_dark", dark = true) {
        TrashContent(TrashFakeData.ready(selection = setOf(2, 4, 5)), TrashActions())
    }

    @Test fun keepForever() = compose.capture("trash_forever_phone") {
        TrashContent(TrashFakeData.ready(retentionDays = 0), TrashActions())
    }

    @Test fun empty() = compose.capture("trash_empty_phone") {
        TrashContent(TrashFakeData.ready(items = emptyList()), TrashActions(onOpenReview = {}))
    }

    @Test fun emptyDark() = compose.capture("trash_empty_phone_dark", dark = true) {
        TrashContent(TrashFakeData.ready(items = emptyList()), TrashActions(onOpenReview = {}))
    }

    @Test fun loading() = compose.capture("trash_loading_phone") {
        TrashContent(TrashUiState(), TrashActions())
    }

    @Test fun error() = compose.capture("trash_error_phone") {
        TrashContent(TrashUiState(phase = TrashPhase.ERROR), TrashActions())
    }

    @Config(qualifiers = UNFOLDED)
    @Test fun unfoldedWithSelection() = compose.capture("trash_selected_unfolded") {
        TrashContent(TrashFakeData.ready(selection = setOf(1, 3)), TrashActions())
    }

    @Test fun confirmDeleteSelected() = captureScreen("trash_confirm_phone") {
        val selected = TrashFakeData.items.filter { it.id in setOf(2L, 4L, 5L) }
        TrashContent(
            TrashFakeData.ready(
                selection = setOf(2, 4, 5),
                confirm = DeleteConfirm(selected.map { it.id }, selected.size, selected.sumOf { it.bytes }, hasRaw = true),
            ),
            TrashActions(),
        )
    }

    @Test fun confirmEmptyTrashDark() = captureScreen("trash_confirm_empty_phone_dark", dark = true) {
        val all = TrashFakeData.items
        TrashContent(
            TrashFakeData.ready(confirm = DeleteConfirm(all.map { it.id }, all.size, all.sumOf { it.bytes }, hasRaw = true)),
            TrashActions(),
        )
    }

    @Test fun menuOpen() = captureScreen("trash_menu_phone") {
        TrashContent(TrashFakeData.ready(selection = setOf(2, 4, 5)), TrashActions(), initialMenuOpen = true)
    }

    @Test fun viewerWithRestore() = compose.capture("trash_viewer_phone") {
        val state = TrashFakeData.ready()
        PhotoViewerContent(
            items = state.items,
            initialIndex = 1,
            onDismiss = {},
            startAtFocus = false,
            bottomBar = { card -> TrashViewerBar(daysLeft = state.daysLeft(card), busy = false, onRestore = {}) },
        )
    }
}
