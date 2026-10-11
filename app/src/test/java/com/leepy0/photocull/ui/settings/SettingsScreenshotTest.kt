package com.leepy0.photocull.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.leepy0.photocull.data.settings.AppSettings
import com.leepy0.photocull.ui.PHONE
import com.leepy0.photocull.ui.ScreenshotFrame
import com.leepy0.photocull.ui.UNFOLDED
import com.leepy0.photocull.ui.capture
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.update.UpdateInfo
import com.leepy0.photocull.update.UpdateState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 목록 전체를 한 장에 담는 세로로 긴 화면 */
private const val TALL = "w392dp-h2600dp-xxhdpi"

private val crashText = """
    2026-10-11 14:03:12 · v0.1.0 (100) · release · abc1234
    Galaxy Z Fold8 · Android 16
    java.lang.IllegalStateException: Bitmap decode failed
        at com.leepy0.photocull.analysis.ImageAnalyzer.decode(ImageAnalyzer.kt:88)
        at com.leepy0.photocull.analysis.AnalysisControllerImpl.run(AnalysisControllerImpl.kt:142)
        at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)
""".trimIndent()

private fun sampleState(
    settings: AppSettings = AppSettings(),
    update: UpdateState = UpdateState.UpToDate(0),
    crash: String? = crashText,
) = SettingsUiState(
    phase = SettingsPhase.READY,
    settings = settings,
    permissions = PermissionStatus(allFiles = true, notifications = false, installPackages = false),
    versionLine = "v0.1.0 (100) · release · abc1234",
    update = update,
    lastCrash = crash,
    exampleMonth = "2026-10",
)

private val availableUpdate = UpdateState.Available(
    UpdateInfo(
        versionName = "0.2.0",
        versionCode = 200,
        apkUrl = "https://example.invalid/PhotoCull.apk",
        sha256 = null,
        sizeBytes = 12_000_000,
        notes = "",
        pageUrl = "https://example.invalid",
        publishedAt = null,
    ),
)

/** AlertDialog와 같은 바탕·모양·여백의 틀(스크린샷용) */
@Composable
private fun DialogLikeFrame(title: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(Spacing.l), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                content()
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class SettingsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    /** 대화상자는 별도 창이라 화면 전체를 찍는다 */
    private fun captureScreen(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { ScreenshotFrame(dark, content) }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun phone() = compose.capture("settings_phone") {
        SettingsContent(sampleState(), SettingsActions())
    }

    @Test fun phoneDark() = compose.capture("settings_phone_dark", dark = true) {
        SettingsContent(sampleState(), SettingsActions())
    }

    @Config(qualifiers = TALL)
    @Test fun fullList() = compose.capture("settings_full") {
        SettingsContent(sampleState(update = availableUpdate), SettingsActions())
    }

    @Config(qualifiers = TALL)
    @Test fun fullListDarkAltValues() = compose.capture("settings_full_dark", dark = true) {
        SettingsContent(
            sampleState(
                settings = AppSettings(
                    destFolderName = "EOS R10 여행",
                    monthlySubfolders = true,
                    moveMode = false,
                    cardNotification = false,
                    burstGapMs = 500,
                    shortVideoMs = 5_000,
                    trashRetentionDays = 0,
                    autoUpdateCheck = false,
                ),
                update = UpdateState.Checking,
                crash = null,
            ),
            SettingsActions(),
        )
    }

    @Config(qualifiers = UNFOLDED)
    @Test fun unfolded() = compose.capture("settings_unfolded") {
        SettingsContent(sampleState(), SettingsActions())
    }

    @Test fun loading() = compose.capture("settings_loading_phone") {
        SettingsContent(SettingsUiState(), SettingsActions())
    }

    @Test fun error() = compose.capture("settings_error_phone") {
        SettingsContent(SettingsUiState(phase = SettingsPhase.ERROR), SettingsActions())
    }

    /**
     * Robolectric에서는 대화상자 창 안의 입력란이 끝내 유휴 상태가 되지 않아(일반 화면의 입력란은 괜찮음)
     * 대화상자 본문을 같은 모양의 틀에 넣어 찍는다.
     */
    @Test fun folderDialogBody() = compose.capture("settings_folder_dialog_phone") {
        DialogLikeFrame(title = "저장 폴더") {
            FolderNameFields(text = "R10/여행", onTextChange = {}, onDone = {})
        }
    }

    /** 실제 폰처럼 한국어 로캘: 어절 단위 줄바꿈(phraseBreak) 확인 */
    @Config(qualifiers = "ko-rKR-$PHONE")
    @Test fun folderDialogBodyKorean() = compose.capture("settings_folder_dialog_ko_phone") {
        DialogLikeFrame(title = "저장 폴더") {
            FolderNameFields(text = "R10/여행", onTextChange = {}, onDone = {})
        }
    }

    @Config(qualifiers = "ko-rKR-$TALL")
    @Test fun fullListKorean() = compose.capture("settings_full_ko", dark = true) {
        SettingsContent(sampleState(settings = AppSettings(burstGapMs = 500, trashRetentionDays = 7)), SettingsActions())
    }

    @Test fun folderDialogBodyBlankDark() = compose.capture("settings_folder_dialog_blank_phone_dark", dark = true) {
        DialogLikeFrame(title = "저장 폴더") {
            FolderNameFields(text = "", onTextChange = {}, onDone = {})
        }
    }

    @Test fun crashDialog() = captureScreen("settings_crash_dialog_phone") {
        SettingsContent(sampleState(), SettingsActions(), initialDialog = SettingsDialog.CRASH)
    }

    @Test fun crashDialogDark() = captureScreen("settings_crash_dialog_phone_dark", dark = true) {
        SettingsContent(sampleState(), SettingsActions(), initialDialog = SettingsDialog.CRASH)
    }

    @Test fun retentionConfirm() = captureScreen("settings_retention_confirm_phone") {
        SettingsContent(
            sampleState().copy(retentionConfirm = RetentionConfirm(days = 7, count = 23, bytes = 812L * 1024 * 1024)),
            SettingsActions(),
        )
    }
}
