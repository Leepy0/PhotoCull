package com.leepy0.photocull.ui.review

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leepy0.photocull.ui.PHONE
import com.leepy0.photocull.ui.UNFOLDED
import com.leepy0.photocull.ui.capture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class GroupScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val group = FakeData.groups.first()

    private fun content(currentId: Long = group.bestId, selection: Set<Long> = group.defaultSelection()) =
        GroupUiState.Content(group = group, selection = selection, currentId = currentId)

    @Test fun groupOnBest() = compose.capture("group_phone") {
        GroupContent(content(), GroupActions())
    }

    @Test fun groupOnBlurryFrameDark() = compose.capture("group_phone_dark", dark = true) {
        GroupContent(content(currentId = 102), GroupActions())
    }

    @Test fun groupNothingSelected() = compose.capture("group_keep_all_phone") {
        GroupContent(content(selection = emptySet()), GroupActions())
    }

    @Test fun groupMissing() = compose.capture("group_missing_phone") {
        GroupContent(GroupUiState.Missing, GroupActions())
    }

    @Config(qualifiers = UNFOLDED)
    @Test fun groupUnfolded() = compose.capture("group_unfolded") {
        GroupContent(content(currentId = 103), GroupActions())
    }

    @Config(qualifiers = UNFOLDED)
    @Test fun groupUnfoldedDark() = compose.capture("group_unfolded_dark", dark = true) {
        GroupContent(content(), GroupActions())
    }
}
