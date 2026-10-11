package com.leepy0.photocull.ui.importer

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.importer.ImportError
import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.storage.CardState
import com.leepy0.photocull.ui.ScreenshotFrame
import com.leepy0.photocull.ui.capture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val ready = CardState.Ready(ImportFakes.volume)

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = KO_PHONE)
class ImportScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scanning() = compose.capture("import_scanning_phone") {
        ImportContent(ImportUiState(import = ImportState.Scanning, card = ready), ImportActions())
    }

    @Test fun noCard() = compose.capture("import_no_card_phone") {
        ImportContent(ImportUiState(import = ImportState.Idle, card = CardState.NotConnected), ImportActions())
    }

    @Test fun needsFolderAccess() = compose.capture("import_folder_access_phone") {
        ImportContent(
            ImportUiState(import = ImportState.Failed(ImportError.NoPermission), card = CardState.NeedsAccess("3A1B-27F0", "EOS_DIGITAL")),
            ImportActions(),
        )
    }

    @Test fun ready() = compose.capture("import_ready_phone") {
        ImportContent(ImportUiState(import = ImportState.Ready(ImportFakes.summary()), card = ready), ImportActions(), showNotificationHint = true)
    }

    @Test fun readyDark() = compose.capture("import_ready_phone_dark", dark = true) {
        ImportContent(ImportUiState(import = ImportState.Ready(ImportFakes.summary()), card = ready), ImportActions())
    }

    @Test fun readyCopyMode() = compose.capture("import_ready_copy_phone") {
        ImportContent(
            ImportUiState(import = ImportState.Ready(ImportFakes.summary(already = 0)), card = ready, moveMode = false),
            ImportActions(),
        )
    }

    @Test fun readyInsufficientSpace() = compose.capture("import_ready_no_space_phone") {
        ImportContent(
            ImportUiState(import = ImportState.Ready(ImportFakes.summary(freeBytes = 9_800 * MB)), card = ready),
            ImportActions(),
        )
    }

    @Test fun readyNothingNew() = compose.capture("import_ready_nothing_new_phone") {
        ImportContent(
            ImportUiState(import = ImportState.Ready(ImportFakes.summary(newFiles = 0, newBytes = 0, already = 456)), card = ready, counts = ImportFakes.counts),
            ImportActions(),
        )
    }

    @Config(qualifiers = KO_UNFOLDED)
    @Test fun readyUnfolded() = compose.capture("import_ready_unfolded") {
        ImportContent(ImportUiState(import = ImportState.Ready(ImportFakes.summary()), card = ready), ImportActions(), showNotificationHint = true)
    }

    @Test fun preparing() = compose.capture("import_preparing_phone") {
        ImportContent(ImportUiState(import = ImportState.Running(ImportFakes.preparing), card = ready), ImportActions())
    }

    @Test fun running() = compose.capture("import_running_phone") {
        ImportContent(
            ImportUiState(
                import = ImportState.Running(ImportFakes.progress(failed = 1)),
                card = ready,
                analysis = AnalysisStatus(running = true, done = 40, total = 118),
            ),
            ImportActions(),
        )
    }

    @Test fun runningDark() = compose.capture("import_running_phone_dark", dark = true) {
        ImportContent(
            ImportUiState(import = ImportState.Running(ImportFakes.progress()), card = ready, analysis = AnalysisStatus(running = true, done = 40, total = 118)),
            ImportActions(),
        )
    }

    @Config(qualifiers = KO_UNFOLDED)
    @Test fun runningUnfolded() = compose.capture("import_running_unfolded") {
        ImportContent(ImportUiState(import = ImportState.Running(ImportFakes.progress()), card = ready), ImportActions())
    }

    @Test fun finishedWithFailures() = compose.capture("import_finished_failures_phone") {
        ImportContent(
            ImportUiState(
                import = ImportState.Finished(ImportFakes.result()),
                card = ready,
                counts = ImportFakes.counts,
                analysis = AnalysisStatus(running = true, done = 90, total = 180),
            ),
            ImportActions(),
        )
    }

    @Test fun finishedFailuresExpanded() {
        compose.setContent {
            ScreenshotFrame {
                ImportContent(
                    ImportUiState(import = ImportState.Finished(ImportFakes.result()), card = ready, counts = ImportFakes.counts),
                    ImportActions(),
                )
            }
        }
        compose.onNodeWithText("실패 2개 · 카드에 그대로 있습니다").assertExists()
        compose.onAllNodesWithText("파일 보기").onFirst().performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/import_finished_expanded_phone.png")
    }

    @Test fun finishedDark() = compose.capture("import_finished_phone_dark", dark = true) {
        ImportContent(
            ImportUiState(import = ImportState.Finished(ImportFakes.result(failed = 0, deleteFailed = 0)), card = ready, counts = ImportFakes.counts),
            ImportActions(),
        )
    }

    @Test fun finishedCancelled() = compose.capture("import_finished_cancelled_phone") {
        ImportContent(
            ImportUiState(
                import = ImportState.Finished(ImportFakes.result(saved = 87, failed = 0, deleteFailed = 0, cancelled = true, moveMode = false)),
                card = ready,
                counts = ImportFakes.counts.copy(groups = 0),
            ),
            ImportActions(),
        )
    }

    @Test fun failedCardRemoved() = compose.capture("import_failed_card_removed_phone") {
        ImportContent(
            ImportUiState(import = ImportState.Failed(ImportError.CardRemoved(120, 300)), card = CardState.NotConnected),
            ImportActions(),
        )
    }

    @Test fun failedNoSpace() = compose.capture("import_failed_no_space_phone") {
        ImportContent(
            ImportUiState(import = ImportState.Failed(ImportError.InsufficientSpace(14 * GB, 9 * GB)), card = ready),
            ImportActions(),
        )
    }
}
