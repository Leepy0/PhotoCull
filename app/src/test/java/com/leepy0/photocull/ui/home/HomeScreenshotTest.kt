package com.leepy0.photocull.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.data.db.SessionState
import com.leepy0.photocull.data.review.ReviewCounts
import com.leepy0.photocull.importer.ImportError
import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.storage.CardState
import com.leepy0.photocull.ui.capture
import com.leepy0.photocull.ui.importer.KO_PHONE
import com.leepy0.photocull.ui.importer.KO_UNFOLDED
import com.leepy0.photocull.ui.components.UiState
import com.leepy0.photocull.ui.importer.DAY_MS
import com.leepy0.photocull.ui.importer.ImportFakes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

object HomeFakes {
    val lastImport = LastImport(
        finishedAt = ImportFakes.now - DAY_MS,
        volumeLabel = "EOS_DIGITAL",
        moveMode = true,
        savedCount = 412,
        duplicateCount = 0,
        failedCount = 0,
        sourceDeleteFailedCount = 2,
        state = SessionState.COMPLETED,
    )

    val backlog = HomeUiState(
        card = CardState.NotConnected,
        import = ImportState.Idle,
        review = UiState.Content(ImportFakes.counts),
        lastImport = lastImport,
        lastImportLoaded = true,
    )

    val cardReady = HomeUiState(
        card = CardState.Ready(ImportFakes.volume),
        import = ImportState.Ready(ImportFakes.summary()),
        review = UiState.Content(ImportFakes.counts),
        lastImport = lastImport,
        lastImportLoaded = true,
        updateAvailable = true,
    )

    val running = HomeUiState(
        card = CardState.Ready(ImportFakes.volume),
        import = ImportState.Running(ImportFakes.progress()),
        review = UiState.Content(ReviewCounts(blur = 3, groups = 2, groupPhotos = 9)),
        analysis = AnalysisStatus(running = true, done = 40, total = 118),
        lastImport = lastImport,
        lastImportLoaded = true,
    )

    val firstRun = HomeUiState(
        card = CardState.NotConnected,
        review = UiState.Content(ReviewCounts()),
        lastImportLoaded = true,
    )
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = KO_PHONE)
class HomeScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun notConnectedWithBacklog() = compose.capture("home_not_connected_phone") {
        HomeContent(HomeFakes.backlog, HomeActions())
    }

    @Test fun notConnectedWithBacklogDark() = compose.capture("home_not_connected_phone_dark", dark = true) {
        HomeContent(HomeFakes.backlog, HomeActions())
    }

    @Test fun interrupted() = compose.capture("home_interrupted_phone") {
        HomeContent(
            HomeFakes.backlog.copy(
                import = ImportState.Failed(ImportError.CardRemoved(120, 300)),
                lastImport = HomeFakes.lastImport.copy(state = SessionState.INTERRUPTED, savedCount = 120, sourceDeleteFailedCount = 0),
            ),
            HomeActions(),
        )
    }

    @Test fun cardReady() = compose.capture("home_card_ready_phone") {
        HomeContent(HomeFakes.cardReady, HomeActions())
    }

    @Test fun cardReadyDark() = compose.capture("home_card_ready_phone_dark", dark = true) {
        HomeContent(HomeFakes.cardReady, HomeActions())
    }

    @Config(qualifiers = KO_UNFOLDED)
    @Test fun cardReadyUnfolded() = compose.capture("home_card_ready_unfolded") {
        HomeContent(HomeFakes.cardReady, HomeActions())
    }

    @Test fun checking() = compose.capture("home_checking_phone") {
        HomeContent(HomeFakes.cardReady.copy(import = ImportState.Scanning, updateAvailable = false), HomeActions())
    }

    @Test fun nothingNew() = compose.capture("home_nothing_new_phone") {
        HomeContent(
            HomeFakes.cardReady.copy(import = ImportState.Ready(ImportFakes.summary(newFiles = 0, newBytes = 0, already = 456)), updateAvailable = false),
            HomeActions(),
        )
    }

    @Test fun running() = compose.capture("home_running_phone") {
        HomeContent(HomeFakes.running, HomeActions())
    }

    @Test fun runningDark() = compose.capture("home_running_phone_dark", dark = true) {
        HomeContent(HomeFakes.running, HomeActions())
    }

    @Test fun finished() = compose.capture("home_finished_phone") {
        HomeContent(
            HomeFakes.cardReady.copy(import = ImportState.Finished(ImportFakes.result()), updateAvailable = false),
            HomeActions(),
        )
    }

    @Test fun noPermission() = compose.capture("home_no_permission_phone") {
        HomeContent(HomeFakes.firstRun.copy(card = CardState.NoPermission), HomeActions())
    }

    @Test fun needsAccess() = compose.capture("home_needs_access_phone") {
        HomeContent(HomeFakes.backlog.copy(card = CardState.NeedsAccess("3A1B-27F0", "EOS_DIGITAL")), HomeActions())
    }

    @Test fun firstRun() = compose.capture("home_first_run_phone") {
        HomeContent(HomeFakes.firstRun, HomeActions())
    }

    @Test fun loading() = compose.capture("home_loading_phone") {
        HomeContent(HomeUiState(), HomeActions())
    }

    @Test fun reviewError() = compose.capture("home_review_error_phone") {
        HomeContent(HomeFakes.backlog.copy(review = UiState.Error(title = "")), HomeActions())
    }
}
