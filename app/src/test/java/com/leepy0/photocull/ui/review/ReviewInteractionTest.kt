package com.leepy0.photocull.ui.review

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
        compose.onNodeWithText("3장 삭제 · ${bytesText(79 * MB)}").performClick()
        // 휴지통 이동이라 공간이 바로 생기지 않는다는 안내(폰에서도)
        compose.onNodeWithText("휴지통으로 옮깁니다 · 휴지통을 비우면 공간이 생깁니다").assertExists()
        assertThat(deleted).isEqualTo(ReviewTab.BLUR)
    }

    @Test fun keepAllShownWhenNothingSelected() {
        var kept: ReviewTab? = null
        compose.setContent {
            ScreenshotFrame { ReviewContent(readyState(ReviewTab.VIDEO), ReviewActions(onKeepRest = { kept = it })) }
        }
        // 아직 아무것도 정리하지 않았으면 '남은'을 붙이지 않는다
        compose.onNodeWithText("5개 모두 유지").performClick()
        compose.onNodeWithText("유지하면 검토 목록에서 빠집니다").assertExists()
        assertThat(kept).isEqualTo(ReviewTab.VIDEO)
    }

    @Test fun keepRestSaysRemainingAfterSomethingWasProcessed() {
        compose.setContent {
            ScreenshotFrame { ReviewContent(readyState(ReviewTab.VIDEO).copy(processedTabs = setOf(ReviewTab.VIDEO)), ReviewActions()) }
        }
        compose.onNodeWithText("남은 5개 모두 유지").assertExists()
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

    @Test fun burstBulkActionIsTheBottomPrimaryAndHeaderOffersKeepAll() {
        var bulk = false
        var kept: ReviewTab? = null
        var opened: Long? = null
        compose.setContent {
            ScreenshotFrame {
                ReviewContent(
                    readyState(ReviewTab.BURST),
                    ReviewActions(onKeepBestAll = { bulk = true }, onKeepRest = { kept = it }, onOpenGroup = { opened = it }),
                )
            }
        }
        compose.onNodeWithText("베스트만 남기고 18장 삭제 · ${bytesText(540 * MB)}").performClick()
        compose.onNodeWithText("그룹마다 ★베스트 1장은 남깁니다").assertExists()
        compose.onNodeWithText("전체가 흐린 1그룹은 베스트도 흐림 탭에서 다시 확인합니다").assertExists()
        compose.onNodeWithText("삭제 없이 모두 유지").performClick()
        compose.onNodeWithText("비슷한 사진 3장").performClick()
        assertThat(bulk).isTrue()
        assertThat(kept).isEqualTo(ReviewTab.BURST)
        assertThat(opened).isEqualTo(200L)
    }

    @Test fun burstWithNothingToDeleteFallsBackToKeepAll() {
        // 그룹마다 베스트만 판단 전이고 나머지는 이미 유지함 → 지울 것이 없다
        val groups = listOf(group(10, listOf(11L, 12L, 13L), bestId = 12, keptIds = setOf(11L, 13L)))
        var kept: ReviewTab? = null
        compose.setContent {
            ScreenshotFrame {
                ReviewContent(readyState(ReviewTab.BURST).copy(groups = groups), ReviewActions(onKeepRest = { kept = it }))
            }
        }
        compose.onNodeWithText("삭제 없이 모두 유지").assertDoesNotExist()
        compose.onNodeWithText("1장 모두 유지").performClick()
        assertThat(kept).isEqualTo(ReviewTab.BURST)
    }

    @Test fun burstHeaderWarnsWhileAnalyzing() {
        compose.setContent {
            ScreenshotFrame {
                ReviewContent(
                    readyState(ReviewTab.BURST).copy(analysis = com.leepy0.photocull.analysis.AnalysisStatus(running = true, done = 1, total = 9)),
                    ReviewActions(),
                )
            }
        }
        compose.onNodeWithText("분석 중이라 그룹이 바뀔 수 있습니다").assertExists()
    }

    @Test fun groupCardIsReadWithItsBlurWarning() {
        compose.setContent { ScreenshotFrame { ReviewContent(readyState(ReviewTab.BURST), ReviewActions()) } }
        // '전체가 흐림'이 카드 설명에 가려지지 않고 함께 읽힌다
        compose.onNode(hasText("전체가 흐림") and hasClickAction()).assertExists()
    }

    @Test fun thumbnailCheckIsAToggleableCheckboxWithState() {
        compose.setContent {
            ScreenshotFrame { ReviewContent(readyState(ReviewTab.BLUR, blurSelection = setOf(1, 3, 4)), ReviewActions()) }
        }
        compose.onAllNodes(hasContentDescription("삭제 대상") and isToggleable() and isOn()).assertCountEquals(3)
        assertThat(compose.onAllNodes(hasContentDescription("삭제 대상") and isToggleable() and isOff()).fetchSemanticsNodes()).isNotEmpty()
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
        compose.onNodeWithText("11장 삭제 · ${bytesText(330 * MB)}").performClick()
        assertThat(pinned).isEqualTo(103L)
        assertThat(deleted).isTrue()
    }

    @Test fun groupBestCannotBeMarked() {
        val group = FakeData.groups.first()
        compose.setContent {
            ScreenshotFrame {
                GroupContent(GroupUiState.Content(group, group.defaultSelection(), currentId = group.bestId), GroupActions())
            }
        }
        compose.onNodeWithText("베스트는 남깁니다").assertIsNotEnabled()
    }

    @Test fun markingInViewerAdvancesToNextPhoto() {
        var marked: Triple<ReviewTab, Long, Boolean>? = null
        compose.setContent {
            ScreenshotFrame {
                ReviewContent(readyState(ReviewTab.BLUR), ReviewActions(onSetMarked = { t, id, m -> marked = Triple(t, id, m) }))
            }
        }
        compose.onNodeWithContentDescription("IMG_1003.JPG 크게 보기").performClick()
        compose.onNodeWithText("IMG_1003.JPG").assertExists()
        compose.onNodeWithText("삭제 대상으로 표시").performClick()
        compose.waitForIdle()
        assertThat(marked).isEqualTo(Triple(ReviewTab.BLUR, 3L, true))
        compose.onNodeWithText("IMG_1004.JPG").assertExists()
    }

    @Test fun viewerPreviousNextButtonsMovePages() {
        compose.setContent {
            ScreenshotFrame { PhotoViewerContent(items = FakeData.blur, initialIndex = 3, onDismiss = {}, startAtFocus = true) }
        }
        compose.onNodeWithText("IMG_1004.JPG").assertExists()
        compose.onNodeWithContentDescription("다음").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("IMG_1005.JPG").assertExists()
        compose.onNodeWithContentDescription("이전").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("이전").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("IMG_1003.JPG").assertExists()
    }

    @Test fun viewerNavigationStopsAtTheEnds() {
        compose.setContent {
            ScreenshotFrame { PhotoViewerContent(items = FakeData.blur, initialIndex = 0, onDismiss = {}, startAtFocus = false) }
        }
        compose.onNodeWithContentDescription("이전").assertIsNotEnabled()
        compose.onNodeWithContentDescription("다음").assertIsEnabled()
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
