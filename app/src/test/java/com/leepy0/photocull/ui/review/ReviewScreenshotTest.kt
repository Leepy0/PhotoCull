package com.leepy0.photocull.ui.review

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.ui.PHONE
import com.leepy0.photocull.ui.UNFOLDED
import com.leepy0.photocull.ui.capture
import com.leepy0.photocull.ui.nav.ReviewTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

fun readyState(
    tab: ReviewTab,
    blurSelection: Set<Long> = emptySet(),
    videoSelection: Set<Long> = emptySet(),
) = ReviewUiState(
    phase = LoadPhase.READY,
    tab = tab,
    blur = FakeData.blur,
    groups = FakeData.groups,
    videos = FakeData.videos,
    blurSelection = blurSelection,
    videoSelection = videoSelection,
)

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ReviewScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun blurWithSelection() = compose.capture("review_blur_phone") {
        ReviewContent(readyState(ReviewTab.BLUR, blurSelection = setOf(1, 3, 4)), ReviewActions())
    }

    @Test fun blurWithSelectionDark() = compose.capture("review_blur_phone_dark", dark = true) {
        ReviewContent(readyState(ReviewTab.BLUR, blurSelection = setOf(1, 3, 4)), ReviewActions())
    }

    @Test fun blurNothingSelected() = compose.capture("review_blur_unselected_phone") {
        ReviewContent(readyState(ReviewTab.BLUR), ReviewActions())
    }

    @Config(qualifiers = UNFOLDED)
    @Test fun blurTwoPaneUnfolded() = compose.capture("review_blur_unfolded") {
        ReviewContent(readyState(ReviewTab.BLUR, blurSelection = setOf(1, 3)), ReviewActions())
    }

    @Config(qualifiers = UNFOLDED)
    @Test fun blurTwoPaneUnfoldedDark() = compose.capture("review_blur_unfolded_dark", dark = true) {
        ReviewContent(readyState(ReviewTab.BLUR, blurSelection = setOf(1)), ReviewActions())
    }

    @Test fun blurEmpty() = compose.capture("review_blur_empty_phone") {
        ReviewContent(readyState(ReviewTab.BLUR).copy(blur = emptyList()), ReviewActions())
    }

    @Test fun blurEmptyWhileAnalyzing() = compose.capture("review_blur_analyzing_phone") {
        ReviewContent(
            readyState(ReviewTab.BLUR).copy(blur = emptyList(), analysis = AnalysisStatus(running = true, done = 120, total = 324)),
            ReviewActions(),
        )
    }

    @Test fun burst() = compose.capture("review_burst_phone") {
        ReviewContent(readyState(ReviewTab.BURST), ReviewActions())
    }

    @Test fun burstDark() = compose.capture("review_burst_phone_dark", dark = true) {
        ReviewContent(readyState(ReviewTab.BURST), ReviewActions())
    }

    @Config(qualifiers = UNFOLDED)
    @Test fun burstUnfolded() = compose.capture("review_burst_unfolded") {
        ReviewContent(readyState(ReviewTab.BURST), ReviewActions())
    }

    @Test fun burstWhileAnalyzing() = compose.capture("review_burst_analyzing_phone") {
        ReviewContent(readyState(ReviewTab.BURST).copy(analysis = AnalysisStatus(running = true, done = 120, total = 324)), ReviewActions())
    }

    @Test fun videoNothingSelected() = compose.capture("review_video_unselected_phone") {
        ReviewContent(readyState(ReviewTab.VIDEO), ReviewActions())
    }

    @Test fun video() = compose.capture("review_video_phone") {
        ReviewContent(readyState(ReviewTab.VIDEO, videoSelection = setOf(51, 52)), ReviewActions())
    }

    @Test fun videoDark() = compose.capture("review_video_phone_dark", dark = true) {
        ReviewContent(readyState(ReviewTab.VIDEO, videoSelection = setOf(51, 52)), ReviewActions())
    }

    @Test fun loading() = compose.capture("review_loading_phone") {
        ReviewContent(ReviewUiState(), ReviewActions())
    }

    @Test fun error() = compose.capture("review_error_phone") {
        ReviewContent(ReviewUiState(phase = LoadPhase.ERROR), ReviewActions())
    }

    @Test fun allDone() = compose.capture("review_all_done_phone") {
        ReviewContent(ReviewUiState(phase = LoadPhase.READY, tab = ReviewTab.VIDEO), ReviewActions())
    }
}
