package com.leepy0.photocull.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leepy0.photocull.ui.PHONE
import com.leepy0.photocull.ui.capture
import com.leepy0.photocull.update.UpdateInfo
import com.leepy0.photocull.update.UpdateState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class UpdateSheetScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val info = UpdateInfo(
        versionName = "0.2.0",
        versionCode = 200,
        apkUrl = "https://github.com/Leepy0/PhotoCull/releases/download/v0.2.0/PhotoCull.apk",
        sha256 = "a".repeat(64),
        sizeBytes = 21_076_377,
        notes = """
            - 흐림 탭에서 민감도를 바꾸면 목록이 바로 바뀝니다
            - 연사 그룹을 한 번에 정리하는 "모든 그룹에서 베스트만 남기기" 추가
            - 가져오기 중 카드를 뽑았을 때 이어서 가져오기가 더 빨라졌습니다
            - 폴드 펼침 화면에서 그룹 비교가 2단으로 보입니다
            - 휴지통 보관 기간을 7일/30일/무기한 중에서 고를 수 있습니다
            - 일부 HIF 사진의 선명도 계산 오류 수정
            - 업데이트 화면 문구 개선
        """.trimIndent(),
        pageUrl = "https://github.com/Leepy0/PhotoCull/releases/tag/v0.2.0",
        publishedAt = "2026-10-11T05:03:00Z",
    )

    private fun update(state: UpdateState, canInstall: Boolean = true, local: Boolean = false, failureAction: FailureAction = FailureAction.RETRY) =
        UpdateSheetMode.Update(
            state = state,
            currentVersionName = "0.1.0",
            canInstall = canInstall,
            localBuild = local,
            failureAction = failureAction,
        )

    @Test fun available() = shot("update_sheet_available") { update(UpdateState.Available(info)) }

    @Test fun availableDark() = shot("update_sheet_available_dark", dark = true) { update(UpdateState.Available(info), local = true) }

    @Test fun downloading() = shot("update_sheet_downloading") { update(UpdateState.Downloading(info, 0.61f)) }

    @Test fun readyNeedsPermission() = shot("update_sheet_ready_permission") {
        update(UpdateState.ReadyToInstall(info), canInstall = false)
    }

    @Test fun readyToInstall() = shot("update_sheet_ready") { update(UpdateState.ReadyToInstall(info)) }

    @Test fun failed() = shot("update_sheet_failed") {
        update(
            UpdateState.Failed(
                message = "업데이트를 확인하지 못했습니다\n인터넷에 연결되어 있지 않거나 서버가 응답하지 않습니다.",
                fix = "네트워크 연결을 확인하고 다시 시도하세요.",
                info = null,
            ),
        )
    }

    @Test fun failedSignatureDark() = shot("update_sheet_failed_signature_dark", dark = true) {
        update(
            UpdateState.Failed(
                message = "서명이 달라 업데이트할 수 없습니다\n설치된 앱과 새 버전의 서명 키가 다릅니다.",
                fix = "기존 앱을 삭제하고 새 버전을 설치하세요. 가져온 사진은 지워지지 않습니다.",
                info = info,
            ),
            local = true,
            failureAction = FailureAction.OPEN_RELEASE_PAGE,
        )
    }

    @Test fun upToDate() = shot("update_sheet_uptodate") { update(UpdateState.UpToDate(checkedAt = 1_791_000_000_000L)) }

    @Test fun checking() = shot("update_sheet_checking") { update(UpdateState.Checking) }

    @Test fun installing() = shot("update_sheet_installing") { update(UpdateState.Installing(info)) }

    @Test fun justUpdated() = shot("update_sheet_just_updated") {
        UpdateSheetMode.JustUpdated(versionName = "0.2.0", change = "v0.1.0 → v0.2.0", notes = info.notes)
    }

    @Test fun crashNotice() = shot("update_sheet_crash") { UpdateSheetMode.Crash(crashedAt = 1_791_000_000_000L, copied = false) }

    @Test fun crashNoticeCopiedDark() = shot("update_sheet_crash_copied_dark", dark = true) {
        UpdateSheetMode.Crash(crashedAt = 1_791_000_000_000L, copied = true)
    }

    private fun shot(name: String, dark: Boolean = false, mode: () -> UpdateSheetMode) =
        compose.capture(name, dark) { SheetFrame { UpdateSheetContent(mode(), UpdateSheetActions()) } }
}

/** ModalBottomSheet 모양 흉내(스크린샷은 팝업 창을 찍지 못한다): 어두운 배경 + 아래쪽 시트 + 손잡이 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetFrame(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0), bottomEnd = CornerSize(0)),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BottomSheetDefaults.DragHandle()
                content()
            }
        }
    }
}
