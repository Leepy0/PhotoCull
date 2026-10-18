package com.leepy0.photocull.ui.importer

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.SdCardAlert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import com.leepy0.photocull.R
import com.leepy0.photocull.ui.components.IosTopBar
import com.leepy0.photocull.importer.ImportError
import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.ui.LocalAppContainer
import com.leepy0.photocull.ui.components.BottomActionBar
import com.leepy0.photocull.ui.components.EmptyState
import com.leepy0.photocull.ui.components.LoadingState
import com.leepy0.photocull.ui.components.PrimaryActionButton
import com.leepy0.photocull.ui.review.AdaptiveActionRow
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.util.Permissions
import kotlinx.coroutines.launch

/** 가져오기 화면이 부르는 동작(스크린샷 테스트에서는 기본값 = 아무것도 안 함) */
@Immutable
data class ImportActions(
    val onBack: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onMoveModeChange: (Boolean) -> Unit = {},
    /** 카드 다시 확인(재스캔) */
    val onRescan: () -> Unit = {},
    val onGrantFolderAccess: (volumeId: String) -> Unit = {},
    val onGrantAllFilesAccess: () -> Unit = {},
    val onOpenStorageSettings: () -> Unit = {},
    /** 결과를 닫고 검토로 */
    val onStartReview: () -> Unit = {},
    /** 새 파일이 없을 때: 결과를 닫지 않고 검토로 */
    val onOpenReview: () -> Unit = {},
)

/**
 * 가져오기(흐름 화면). 주요 행동: [가져오기 시작].
 * 카드 확인 → 요약(공간·카드 원본 선택) → 진행(나가도 계속) → 결과(실패 사유, 꺼내기 안내) → [검토 시작].
 */
@Composable
fun ImportScreen(onBack: () -> Unit, onOpenReview: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: ImportViewModel = viewModel { ImportViewModel(container, createSavedStateHandle()) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val settingsUnavailable = stringResource(R.string.import_settings_unavailable)
    val showSettingsError: () -> Unit = { scope.launch { snackbar.showSnackbar(settingsUnavailable) } }

    var notificationsOk by remember { mutableStateOf(notificationsAllowed(context)) }
    // 시작 직전 알림 권한은 이 화면에서 한 번만 묻는다(거절해도 가져오기는 그대로 시작)
    var notificationAsked by rememberSaveable { mutableStateOf(false) }
    val notificationRequester = rememberNotificationPermissionRequester(onResult = { granted, _ -> notificationsOk = granted })
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsOk = notificationsAllowed(context)
        vm.onResume()
    }

    val folderAccess = rememberFolderAccessLauncher(
        onGranted = vm::onFolderAccessGranted,
        onUnavailable = vm::refreshCard,
    )

    // 결과·오류를 보고 나가면 닫는다(다음에 들어오면 카드를 새로 확인)
    val leave: () -> Unit = {
        vm.dismissResult()
        onBack()
    }
    BackHandler(enabled = state.import is ImportState.Finished || state.import is ImportState.Failed, onBack = leave)

    ImportContent(
        state = state,
        showNotificationHint = !notificationsOk,
        snackbarHostState = snackbar,
        actions = ImportActions(
            onBack = leave,
            onStart = {
                val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Permissions.hasNotificationPermission(context)
                if (canAsk && !notificationAsked) {
                    notificationAsked = true
                    notificationRequester.request()
                }
                vm.start()
            },
            onCancel = vm::cancel,
            onMoveModeChange = vm::setMoveMode,
            onRescan = vm::rescan,
            onGrantFolderAccess = { volumeId -> folderAccess(volumeId, vm.folderAccessIntent(volumeId)) },
            onGrantAllFilesAccess = { if (!context.openAllFilesAccessSettings()) showSettingsError() },
            onOpenStorageSettings = { if (!context.openStorageSettings()) showSettingsError() },
            onStartReview = {
                vm.dismissResult()
                onOpenReview()
            },
            onOpenReview = onOpenReview,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportContent(
    state: ImportUiState,
    actions: ImportActions,
    modifier: Modifier = Modifier,
    showNotificationHint: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val view = state.view
    KoreanLineBreaks {
        Scaffold(
            modifier = modifier,
            topBar = {
                IosTopBar(
                    navigationIcon = {
                        IconButton(onClick = actions.onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    title = { Text(stringResource(R.string.import_title)) },
                )
            },
            bottomBar = { ImportBottomBar(state, view, actions, showNotificationHint) },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                val wide = maxWidth >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp
                when (view) {
                    ImportView.Checking -> LoadingState(stringResource(R.string.import_checking))
                    is ImportView.NoCard -> if (view.notCameraLabel != null) {
                        EmptyState(
                            icon = Icons.Outlined.SdCardAlert,
                            title = stringResource(R.string.import_not_camera_title),
                            body = stringResource(R.string.import_not_camera_body, view.notCameraLabel),
                            actionLabel = stringResource(R.string.import_check_again),
                            onAction = actions.onRescan,
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Outlined.SdCard,
                            title = stringResource(R.string.import_no_card_title),
                            body = stringResource(R.string.import_no_card_body),
                            actionLabel = stringResource(R.string.import_check_again),
                            onAction = actions.onRescan,
                        )
                    }
                    is ImportView.NeedsFolderAccess -> EmptyState(
                        icon = Icons.Outlined.FolderOpen,
                        title = stringResource(R.string.import_folder_title),
                        body = stringResource(R.string.import_folder_body, view.label),
                    )
                    ImportView.NeedsPermission -> EmptyState(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.import_permission_title),
                        body = stringResource(R.string.import_permission_body),
                    )
                    is ImportView.Ready -> if (view.summary.newFileCount <= 0) {
                        NothingNewContent(view.summary)
                    } else {
                        ReadyContent(
                            summary = view.summary,
                            moveMode = state.moveMode,
                            wide = wide,
                            onMoveModeChange = actions.onMoveModeChange,
                            onOpenStorageSettings = actions.onOpenStorageSettings,
                        )
                    }
                    is ImportView.Running -> RunningContent(view.progress, state.analysis, wide)
                    is ImportView.Finished -> FinishedContent(view.result, state.analysis, wide, actions.onOpenStorageSettings)
                    is ImportView.Failed -> FailedContent(view.error)
                }
            }
        }
    }
}

@Composable
private fun ImportBottomBar(
    state: ImportUiState,
    view: ImportView,
    actions: ImportActions,
    showNotificationHint: Boolean,
) {
    when (view) {
        ImportView.Checking, is ImportView.NoCard -> Unit
        is ImportView.NeedsFolderAccess -> ActionBar(hint = null) {
            PrimaryActionButton(stringResource(R.string.import_folder_action), onClick = { actions.onGrantFolderAccess(view.volumeId) })
        }
        ImportView.NeedsPermission -> ActionBar(hint = null) {
            PrimaryActionButton(stringResource(R.string.import_permission_action), onClick = actions.onGrantAllFilesAccess)
        }
        is ImportView.Ready -> {
            val s = view.summary
            when {
                s.newFileCount <= 0 -> ActionBar(hint = null) {
                    if (state.counts.hasWork(state.analysis)) {
                        PrimaryActionButton(stringResource(R.string.import_go_review), onClick = actions.onOpenReview)
                    } else {
                        PrimaryActionButton(stringResource(R.string.import_go_back), onClick = actions.onBack)
                    }
                }
                !s.enoughSpace -> ActionBar(hint = null) {
                    PrimaryActionButton(
                        stringResource(R.string.import_start_no_space, sizeText(s.shortageBytes)),
                        onClick = {},
                        enabled = false,
                    )
                }
                else -> ActionBar(
                    hint = if (showNotificationHint) stringResource(R.string.import_notification_hint) else null,
                    showHintWhenNarrow = true,
                ) {
                    PrimaryActionButton(
                        stringResource(R.string.import_start, countText(s.newFileCount), sizeText(s.newBytes)),
                        onClick = actions.onStart,
                    )
                }
            }
        }
        is ImportView.Running -> ActionBar(hint = stringResource(R.string.import_cancel_hint), showHintWhenNarrow = true) {
            // 취소는 안전한 동작이라 확인 없이 바로. 주요 버튼처럼 보이지 않게 외곽선 버튼으로 내용과 떨어뜨려 둔다.
            OutlinedButton(
                onClick = actions.onCancel,
                enabled = !state.cancelRequested,
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
            ) {
                Text(stringResource(if (state.cancelRequested) R.string.import_cancelling else R.string.import_cancel))
            }
        }
        is ImportView.Finished -> ActionBar(hint = null) {
            val parts = reviewPartsText(state.counts, state.analysis)
            if (view.result.savedCount > 0 || parts != null) {
                PrimaryActionButton(
                    if (parts != null) stringResource(R.string.import_review_start_with, parts) else stringResource(R.string.import_review_start),
                    onClick = actions.onStartReview,
                )
            } else {
                PrimaryActionButton(stringResource(R.string.import_done_close), onClick = actions.onBack)
            }
        }
        is ImportView.Failed -> ActionBar(hint = null) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (view.error is ImportError.InsufficientSpace) {
                    OutlinedButton(
                        onClick = actions.onOpenStorageSettings,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
                    ) { Text(stringResource(R.string.import_storage_settings)) }
                }
                PrimaryActionButton(stringResource(R.string.import_check_again), onClick = actions.onRescan)
            }
        }
    }
}

@Composable
private fun ActionBar(hint: String?, showHintWhenNarrow: Boolean = false, action: @Composable () -> Unit) {
    BottomActionBar {
        AdaptiveActionRow(hint = hint, showHintWhenNarrow = showHintWhenNarrow, action = action)
    }
}

/** 새로 가져올 파일이 없음. 카드 정리 같은 되돌릴 수 없는 추가 동작은 권하지 않는다. */
@Composable
private fun NothingNewContent(summary: com.leepy0.photocull.importer.CardScanSummary) {
    EmptyState(
        icon = Icons.Outlined.SdCard,
        title = stringResource(R.string.import_nothing_new_title),
        body = stringResource(R.string.import_nothing_new_body, countText(summary.fileCount)),
    )
}

/** 화면 폭이 넓을 때 내용 최대 폭 */
internal val ContentMaxWidth = Spacing.gridCellMin * 6
