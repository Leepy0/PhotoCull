package com.leepy0.photocull.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leepy0.photocull.R
import com.leepy0.photocull.data.settings.BlurSensitivity
import com.leepy0.photocull.data.settings.ThemeMode
import com.leepy0.photocull.storage.StoragePaths
import com.leepy0.photocull.ui.LocalAppContainer
import com.leepy0.photocull.ui.components.ErrorState
import com.leepy0.photocull.ui.components.LoadingState
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.update.UpdateState
import com.leepy0.photocull.util.Format
import com.leepy0.photocull.util.Permissions
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 설정 화면이 부르는 동작 모음(스크린샷 테스트에서는 기본값 = 아무것도 안 함) */
@Immutable
data class SettingsActions(
    val onDestFolder: (String) -> Unit = {},
    val onMonthly: (Boolean) -> Unit = {},
    val onMoveMode: (Boolean) -> Unit = {},
    val onCardNotification: (Boolean) -> Unit = {},
    val onBlurSensitivity: (BlurSensitivity) -> Unit = {},
    val onBurstGap: (Long) -> Unit = {},
    val onShortVideo: (Long) -> Unit = {},
    val onRetention: (Int) -> Unit = {},
    val onConfirmRetention: () -> Unit = {},
    val onDismissRetention: () -> Unit = {},
    val onTheme: (ThemeMode) -> Unit = {},
    val onAutoUpdate: (Boolean) -> Unit = {},
    val onCheckUpdate: () -> Unit = {},
    val onOpenReleases: () -> Unit = {},
    val onOpenAllFiles: () -> Unit = {},
    val onOpenNotifications: () -> Unit = {},
    val onOpenInstall: () -> Unit = {},
    val onOpenOnboarding: () -> Unit = {},
    val onCopyCrash: (String) -> Unit = {},
    val onClearCrash: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/** 설정 화면이 띄우는 대화상자 */
enum class SettingsDialog { FOLDER, CRASH }

@Composable
fun SettingsScreen(onOpenOnboarding: () -> Unit, onOpenUpdate: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container, context.applicationContext) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 시스템 설정에서 권한을 바꾸고 돌아오면 다시 확인
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(vm) {
        vm.messages.collect { message ->
            when (message) {
                SettingsMessage.SaveFailed -> snackbar.showSnackbar(context.getString(R.string.settings_save_failed))
            }
        }
    }

    fun notify(res: Int) {
        scope.launch { snackbar.showSnackbar(context.getString(res)) }
    }

    fun openSettings(vararg intents: Intent) {
        if (intents.none { context.tryStart(it) }) notify(R.string.settings_open_failed)
    }

    SettingsContent(
        state = state,
        snackbarHostState = snackbar,
        actions = SettingsActions(
            onDestFolder = vm::setDestFolder,
            onMonthly = vm::setMonthly,
            onMoveMode = vm::setMoveMode,
            onCardNotification = vm::setCardNotification,
            onBlurSensitivity = vm::setBlurSensitivity,
            onBurstGap = vm::setBurstGap,
            onShortVideo = vm::setShortVideo,
            onRetention = vm::requestRetention,
            onConfirmRetention = vm::confirmRetention,
            onDismissRetention = vm::dismissRetention,
            onTheme = vm::setTheme,
            onAutoUpdate = vm::setAutoUpdate,
            onCheckUpdate = {
                vm.checkUpdate()
                onOpenUpdate()
            },
            onOpenReleases = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(container.update.releasesPageUrl))
                if (!context.tryStart(intent)) notify(R.string.settings_browser_failed)
            },
            onOpenAllFiles = {
                openSettings(Permissions.allFilesAccessIntent(context), Permissions.allFilesAccessFallbackIntent())
            },
            onOpenNotifications = { openSettings(Permissions.appNotificationSettingsIntent(context)) },
            onOpenInstall = { openSettings(container.update.installPermissionSettingsIntent()) },
            onOpenOnboarding = onOpenOnboarding,
            onCopyCrash = { text -> context.copyToClipboard(context.getString(R.string.settings_crash_clip_label), text) },
            onClearCrash = vm::clearCrash,
            onRetry = vm::retry,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    /** 스크린샷 테스트용: 대화상자를 연 채로 그린다 */
    initialDialog: SettingsDialog? = null,
) {
    var dialog by rememberSaveable { mutableStateOf(initialDialog) }
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (state.phase) {
            SettingsPhase.LOADING -> LoadingState(modifier = Modifier.padding(padding))
            SettingsPhase.ERROR -> ErrorState(
                title = stringResource(R.string.settings_error_title),
                cause = stringResource(R.string.settings_error_cause),
                fix = stringResource(R.string.settings_error_fix),
                onRetry = actions.onRetry,
                modifier = Modifier.padding(padding),
            )
            SettingsPhase.READY -> SettingsList(state, actions, padding, onOpenDialog = { dialog = it })
        }
    }

    when (dialog) {
        SettingsDialog.FOLDER -> FolderNameDialog(
            current = state.settings.destFolderName,
            onSave = {
                actions.onDestFolder(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.CRASH -> state.lastCrash?.let { report ->
            CrashReportDialog(
                report = report,
                onCopy = { actions.onCopyCrash(report) },
                onClear = {
                    actions.onClearCrash()
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }
        null -> Unit
    }

    state.retentionConfirm?.let {
        RetentionConfirmDialog(confirm = it, onConfirm = actions.onConfirmRetention, onDismiss = actions.onDismissRetention)
    }
}

@Composable
private fun SettingsList(
    state: SettingsUiState,
    actions: SettingsActions,
    padding: PaddingValues,
    onOpenDialog: (SettingsDialog) -> Unit,
) {
    val s = state.settings
    val direction = LocalLayoutDirection.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = padding.calculateStartPadding(direction),
            end = padding.calculateEndPadding(direction),
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + Spacing.l,
        ),
    ) {
        // ---- 가져오기 ----
        item { SettingsSectionHeader(stringResource(R.string.settings_section_import), first = true) }
        item {
            SettingsClickItem(
                icon = Icons.Outlined.Folder,
                title = stringResource(R.string.settings_dest_folder),
                supporting = StoragePaths.destDisplayPath(s.destFolderName),
                onClick = { onOpenDialog(SettingsDialog.FOLDER) },
                trailing = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            )
        }
        item {
            SettingsSwitchItem(
                icon = Icons.Outlined.CalendarMonth,
                title = stringResource(R.string.settings_monthly),
                supporting = stringResource(
                    R.string.settings_monthly_desc,
                    StoragePaths.destDisplayPath(s.destFolderName) + "/" + state.exampleMonth,
                ),
                checked = s.monthlySubfolders,
                onCheckedChange = actions.onMonthly,
            )
        }
        item {
            SettingsChoiceItem(
                icon = Icons.Outlined.SdCard,
                title = stringResource(R.string.settings_card_source),
                supporting = stringResource(
                    if (s.moveMode) R.string.settings_card_source_move_desc else R.string.settings_card_source_copy_desc,
                ),
                options = listOf(true, false),
                selected = s.moveMode,
                label = { move ->
                    stringResource(if (move) R.string.settings_card_source_move else R.string.settings_card_source_copy)
                },
                onSelect = actions.onMoveMode,
            )
        }
        item {
            SettingsSwitchItem(
                icon = Icons.Outlined.NotificationsActive,
                title = stringResource(R.string.settings_card_notification),
                supporting = stringResource(R.string.settings_card_notification_desc),
                checked = s.cardNotification,
                onCheckedChange = actions.onCardNotification,
            )
        }

        // ---- 검토 ----
        item { SettingsSectionHeader(stringResource(R.string.settings_section_review)) }
        item {
            SettingsChoiceItem(
                icon = Icons.Outlined.BlurOn,
                title = stringResource(R.string.settings_blur),
                supporting = stringResource(
                    when (s.blurSensitivity) {
                        BlurSensitivity.LOW -> R.string.settings_blur_low_desc
                        BlurSensitivity.NORMAL -> R.string.settings_blur_normal_desc
                        BlurSensitivity.HIGH -> R.string.settings_blur_high_desc
                    },
                ),
                options = BlurSensitivity.entries,
                selected = s.blurSensitivity,
                label = { value ->
                    stringResource(
                        when (value) {
                            BlurSensitivity.LOW -> R.string.settings_blur_low
                            BlurSensitivity.NORMAL -> R.string.settings_blur_normal
                            BlurSensitivity.HIGH -> R.string.settings_blur_high
                        },
                    )
                },
                onSelect = actions.onBlurSensitivity,
            )
        }
        item {
            SettingsChoiceItem(
                icon = Icons.Outlined.BurstMode,
                title = stringResource(R.string.settings_burst_gap),
                supporting = stringResource(R.string.settings_burst_gap_desc, secondsLabel(s.burstGapMs)),
                options = SettingsOptions.burstGapsMs,
                selected = s.burstGapMs,
                label = { secondsLabel(it) },
                onSelect = actions.onBurstGap,
            )
        }
        item {
            SettingsChoiceItem(
                icon = Icons.Outlined.Timer,
                title = stringResource(R.string.settings_short_video),
                supporting = stringResource(R.string.settings_short_video_desc, secondsLabel(s.shortVideoMs)),
                options = SettingsOptions.shortVideosMs,
                selected = s.shortVideoMs,
                label = { secondsLabel(it) },
                onSelect = actions.onShortVideo,
            )
        }

        // ---- 휴지통 ----
        item { SettingsSectionHeader(stringResource(R.string.settings_section_trash)) }
        item {
            SettingsChoiceItem(
                icon = Icons.Outlined.AutoDelete,
                title = stringResource(R.string.settings_retention),
                supporting = if (s.trashRetentionDays > 0) {
                    stringResource(R.string.settings_retention_days_desc, s.trashRetentionDays)
                } else {
                    stringResource(R.string.settings_retention_forever_desc)
                },
                options = SettingsOptions.retentionDays,
                selected = s.trashRetentionDays,
                label = { days ->
                    if (days > 0) stringResource(R.string.settings_retention_days, days) else stringResource(R.string.settings_retention_forever)
                },
                onSelect = actions.onRetention,
            )
        }

        // ---- 화면 ----
        item { SettingsSectionHeader(stringResource(R.string.settings_section_display)) }
        item {
            SettingsChoiceItem(
                icon = Icons.Outlined.Palette,
                title = stringResource(R.string.settings_theme),
                supporting = stringResource(
                    when (s.themeMode) {
                        ThemeMode.SYSTEM -> R.string.settings_theme_system_desc
                        ThemeMode.LIGHT -> R.string.settings_theme_light_desc
                        ThemeMode.DARK -> R.string.settings_theme_dark_desc
                    },
                ),
                options = ThemeMode.entries,
                selected = s.themeMode,
                label = { mode ->
                    stringResource(
                        when (mode) {
                            ThemeMode.SYSTEM -> R.string.settings_theme_system
                            ThemeMode.LIGHT -> R.string.settings_theme_light
                            ThemeMode.DARK -> R.string.settings_theme_dark
                        },
                    )
                },
                onSelect = actions.onTheme,
            )
        }

        // ---- 권한 ----
        item { SettingsSectionHeader(stringResource(R.string.settings_section_permissions)) }
        item {
            SettingsPermissionItem(
                icon = Icons.Outlined.FolderOpen,
                title = stringResource(R.string.settings_perm_all_files),
                supporting = stringResource(R.string.settings_perm_all_files_desc),
                granted = state.permissions.allFiles,
                need = PermissionNeed.REQUIRED,
                onClick = actions.onOpenAllFiles,
            )
        }
        item {
            SettingsPermissionItem(
                icon = Icons.Outlined.Notifications,
                title = stringResource(R.string.settings_perm_notifications),
                supporting = stringResource(R.string.settings_perm_notifications_desc),
                granted = state.permissions.notifications,
                need = PermissionNeed.RECOMMENDED,
                onClick = actions.onOpenNotifications,
            )
        }
        item {
            SettingsPermissionItem(
                icon = Icons.Outlined.InstallMobile,
                title = stringResource(R.string.settings_perm_install),
                supporting = stringResource(R.string.settings_perm_install_desc),
                granted = state.permissions.installPackages,
                need = PermissionNeed.OPTIONAL,
                onClick = actions.onOpenInstall,
            )
        }
        item {
            SettingsClickItem(
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                title = stringResource(R.string.settings_onboarding),
                supporting = stringResource(R.string.settings_onboarding_desc),
                onClick = actions.onOpenOnboarding,
            )
        }

        // ---- 앱 정보 ----
        item { SettingsSectionHeader(stringResource(R.string.settings_section_about)) }
        item { SettingsInfoItem(Icons.Outlined.Info, stringResource(R.string.settings_version), state.versionLine) }
        item {
            SettingsSwitchItem(
                icon = Icons.Outlined.Autorenew,
                title = stringResource(R.string.settings_auto_update),
                supporting = stringResource(R.string.settings_auto_update_desc),
                checked = s.autoUpdateCheck,
                onCheckedChange = actions.onAutoUpdate,
            )
        }
        item {
            SettingsClickItem(
                icon = Icons.Outlined.SystemUpdate,
                title = stringResource(R.string.settings_check_update),
                supporting = updateText(state.update, s.lastUpdateCheckAt),
                onClick = actions.onCheckUpdate,
                trailing = when (state.update) {
                    UpdateState.Checking -> {
                        { CircularProgressIndicator(Modifier.size(Spacing.iconMedium)) }
                    }
                    is UpdateState.Available, is UpdateState.ReadyToInstall -> {
                        { Icon(Icons.Outlined.NewReleases, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    }
                    else -> null
                },
            )
        }
        item {
            SettingsClickItem(
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                title = stringResource(R.string.settings_releases),
                supporting = stringResource(R.string.settings_releases_desc),
                onClick = actions.onOpenReleases,
            )
        }
        item {
            if (state.lastCrash != null) {
                SettingsClickItem(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.settings_crash_view),
                    supporting = stringResource(R.string.settings_crash_view_desc),
                    onClick = { onOpenDialog(SettingsDialog.CRASH) },
                )
            } else {
                SettingsClickItem(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.settings_crash_none),
                    supporting = stringResource(R.string.settings_crash_none_desc),
                    onClick = null,
                )
            }
        }
    }
}

@Composable
private fun secondsLabel(ms: Long): String = stringResource(R.string.settings_seconds, SettingsOptions.seconds(ms))

@Composable
private fun updateText(update: UpdateState, lastCheckedAt: Long): String = when (update) {
    UpdateState.Idle ->
        if (lastCheckedAt > 0) stringResource(R.string.settings_update_last_checked, Format.dateTime(lastCheckedAt))
        else stringResource(R.string.settings_update_idle)
    UpdateState.Checking -> stringResource(R.string.settings_update_checking)
    is UpdateState.UpToDate -> stringResource(R.string.settings_update_up_to_date)
    is UpdateState.Available -> stringResource(R.string.settings_update_available, update.info.versionName)
    is UpdateState.Downloading ->
        if (update.progress >= 0f) {
            stringResource(R.string.settings_update_downloading_percent, update.info.versionName, (update.progress * 100).roundToInt())
        } else {
            stringResource(R.string.settings_update_downloading, update.info.versionName)
        }
    is UpdateState.ReadyToInstall -> stringResource(R.string.settings_update_ready, update.info.versionName)
    is UpdateState.Installing -> stringResource(R.string.settings_update_installing, update.info.versionName)
    is UpdateState.Failed -> update.message.ifBlank { stringResource(R.string.settings_update_failed) }
}

/** 설정·브라우저 화면 열기. 받을 앱이 없거나 막혀 있으면 false(앱이 죽지 않게) */
private fun Context.tryStart(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: RuntimeException) {
    false
}

private fun Context.copyToClipboard(label: String, text: String) {
    runCatching {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
