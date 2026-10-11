package com.leepy0.photocull.ui.review

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.trash.TrashBatch
import com.leepy0.photocull.ui.PHONE
import com.leepy0.photocull.ui.ScreenshotFrame
import com.leepy0.photocull.ui.nav.ReviewTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 검토·그룹 화면이 함께 쓰는 결과 알림(실행 취소 스낵바)과 탭 요청 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ReviewEventsTest {
    @get:Rule val compose = createComposeRule()

    private val trashed = ReviewMessage.Trashed(3, 0, CountUnit.PHOTO, TrashBatch(listOf(1L, 2L, 3L)))

    @Test fun undoableResultStaysUntilHandledAndNewWorkReplacesIt() {
        val events = ReviewEvents()
        events.post(trashed)
        // 실패 알림이 와도 마지막 실행 취소는 남는다
        events.post(ReviewMessage.ActionFailed)
        assertThat(events.undo.value?.message).isEqualTo(trashed)
        assertThat(events.notice.value?.message).isEqualTo(ReviewMessage.ActionFailed)

        val kept = ReviewMessage.Kept(listOf(9L), CountUnit.VIDEO)
        events.post(kept)
        assertThat(events.undo.value?.message).isEqualTo(kept)
        assertThat(events.notice.value).isNull()

        // 오래된 seq로는 지우지 않는다
        events.clear(seq = -1)
        assertThat(events.undo.value?.message).isEqualTo(kept)
        events.clear(events.undo.value!!.seq)
        assertThat(events.undo.value).isNull()
    }

    @Test fun tabRequestIsConsumedOnce() {
        val events = ReviewEvents()
        events.requestTab(ReviewTab.BURST)
        assertThat(events.consumeTabRequest()).isEqualTo(ReviewTab.BURST)
        assertThat(events.consumeTabRequest()).isNull()
    }

    @Test fun snackbarShowsOnWhicheverScreenIsVisibleUntilUndo() {
        val events = ReviewEvents()
        val screen = mutableStateOf("review")
        val undone = mutableListOf<ReviewMessage>()
        compose.setContent {
            ScreenshotFrame {
                // 화면이 바뀌면(검토 → 그룹) 스낵바 호스트도 새로 생긴다
                when (screen.value) {
                    "review" -> SnackbarScreen(events, undone)
                    else -> SnackbarScreen(events, undone)
                }
            }
        }
        events.post(trashed)
        compose.onNodeWithText("3장을 휴지통으로 옮겼습니다").assertExists()

        // 그룹 화면으로 가도 실행 취소가 사라지지 않는다
        screen.value = "group"
        compose.waitForIdle()
        compose.onNodeWithText("3장을 휴지통으로 옮겼습니다").assertExists()

        compose.onNodeWithText("실행 취소").performClick()
        compose.waitForIdle()
        assertThat(undone).containsExactly(trashed)
        assertThat(events.undo.value).isNull()
        compose.onNodeWithText("3장을 휴지통으로 옮겼습니다").assertDoesNotExist()
    }

    @androidx.compose.runtime.Composable
    private fun SnackbarScreen(events: ReviewEvents, undone: MutableList<ReviewMessage>) {
        val host = remember { SnackbarHostState() }
        ReviewMessagesEffect(events, host) { undone += it }
        SnackbarHost(host)
    }
}
