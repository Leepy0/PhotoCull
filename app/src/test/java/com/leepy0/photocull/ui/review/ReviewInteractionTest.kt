package com.leepy0.photocull.ui.review

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.data.settings.BlurSensitivity
import com.leepy0.photocull.ui.PHONE
import com.leepy0.photocull.ui.ScreenshotFrame
import com.leepy0.photocull.ui.nav.ReviewTab
import com.leepy0.photocull.ui.viewer.PhotoViewerContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ReviewInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deleteButtonShowsCountAndBytesAndCallsDelete() {
        var deleted: ReviewTab? = null
        compose.setContent {
            ScreenshotFrame {
                ReviewContent(readyState(ReviewTab.BLUR, blurSelection = setOf(1, 3, 4)), ReviewActions(onDelete = { deleted = it }))
            }
        }
        compose.onNodeWithText("3장 삭제 · ${bytesText(79 * MB)} 확보").performClick()
        assertThat(deleted).isEqualTo(ReviewTab.BLUR)
    }

    @Test fun keepRestShownWhenNothingSelected() {
        var kept: ReviewTab? = null
        compose.setContent {
            ScreenshotFrame { ReviewContent(readyState(ReviewTab.VIDEO), ReviewActions(onKeepRest = { kept = it })) }
        }
        compose.onNodeWithText("남은 5개 모두 유지").performClick()
        assertThat(kept).isEqualTo(ReviewTab.VIDEO)
    }

    @Test fun sensitivityAndSelectAllAreWired() {
        var sensitivity: BlurSensitivity? = null
        var toggledAll: ReviewTab? = null
        compose.setContent {
            ScreenshotFrame {
                ReviewContent(
                    readyState(ReviewTab.BLUR),
                    ReviewActions(onSensitivity = { sensitivity = it }, onToggleAll = { toggledAll = it }),
                )
            }
        }
        compose.onNodeWithText("높음").performClick()
        compose.onNodeWithText("모두 선택").performClick()
        assertThat(sensitivity).isEqualTo(BlurSensitivity.HIGH)
        assertThat(toggledAll).isEqualTo(ReviewTab.BLUR)
    }

    @Test fun emptyTabOffersNextTabWithItems() {
        var selected: ReviewTab? = null
        compose.setContent {
            ScreenshotFrame {
                ReviewContent(readyState(ReviewTab.BLUR).copy(blur = emptyList()), ReviewActions(onSelectTab = { selected = it }))
            }
        }
        compose.onNodeWithText("연사 보기 (3그룹)").performClick()
        assertThat(selected).isEqualTo(ReviewTab.BURST)
    }

    @Test fun allDoneOffersHome() {
        var home = false
        compose.setContent {
            ScreenshotFrame { ReviewContent(ReviewUiState(phase = LoadPhase.READY), ReviewActions(onOpenHome = { home = true })) }
        }
        compose.onNodeWithText("모두 정리했습니다").assertExists()
        compose.onNodeWithText("홈으로").performClick()
        assertThat(home).isTrue()
    }

    @Test fun burstBulkActionAndGroupOpen() {
        var bulk = false
        var opened: Long? = null
        compose.setContent {
            ScreenshotFrame {
                ReviewContent(
                    readyState(ReviewTab.BURST),
                    ReviewActions(onKeepBestAll = { bulk = true }, onOpenGroup = { opened = it }),
                )
            }
        }
        compose.onNodeWithText("모든 그룹에서 베스트만 남기기 (18장 삭제)").performClick()
        compose.onNodeWithText("비슷한 사진 3장").performClick()
        assertThat(bulk).isTrue()
        assertThat(opened).isEqualTo(200L)
    }

    @Test fun busyDisablesPrimaryAction() {
        compose.setContent {
            ScreenshotFrame { ReviewContent(readyState(ReviewTab.BLUR, blurSelection = setOf(1)).copy(busy = true), ReviewActions()) }
        }
        compose.onNodeWithText("처리 중").assertIsNotEnabled()
    }

    @Test fun groupPinBestAndDelete() {
        val group = FakeData.groups.first()
        var pinned: Long? = null
        var deleted = false
        compose.setContent {
            ScreenshotFrame {
                GroupContent(
                    GroupUiState.Content(group, group.defaultSelection(), currentId = 103),
                    GroupActions(onPinBest = { pinned = it }, onDelete = { deleted = true }),
                )
            }
        }
        compose.onNodeWithText("베스트로 지정").performClick()
        compose.onNodeWithText("11장 삭제 · ${bytesText(330 * MB)} 확보").performClick()
        assertThat(pinned).isEqualTo(103L)
        assertThat(deleted).isTrue()
    }

    @Test fun viewerStaysOnSameItemWhenListChanges() {
        val items = mutableStateOf(FakeData.blur)
        compose.setContent {
            ScreenshotFrame { PhotoViewerContent(items = items.value, initialIndex = 3, onDismiss = {}, startAtFocus = false) }
        }
        compose.onNodeWithText("IMG_1004.JPG").assertExists()

        // 앞쪽 항목이 사라져도 보던 사진 유지
        items.value = FakeData.blur.filter { it.id != 1L }
        compose.waitForIdle()
        compose.onNodeWithText("IMG_1004.JPG").assertExists()

        // 보던 사진이 사라지면 같은 자리의 다음 사진
        items.value = items.value.filter { it.id != 4L }
        compose.waitForIdle()
        compose.onNodeWithText("IMG_1005.JPG").assertExists()
    }

    @Test fun viewerClosesWhenListBecomesEmpty() {
        val items = mutableStateOf(FakeData.blur.take(1))
        var dismissed = false
        compose.setContent {
            ScreenshotFrame { PhotoViewerContent(items = items.value, initialIndex = 0, onDismiss = { dismissed = true }) }
        }
        items.value = emptyList()
        compose.waitForIdle()
        assertThat(dismissed).isTrue()
    }
}
