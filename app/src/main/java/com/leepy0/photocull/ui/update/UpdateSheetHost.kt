package com.leepy0.photocull.ui.update

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leepy0.photocull.R
import com.leepy0.photocull.diagnostics.CrashReporter
import com.leepy0.photocull.ui.LocalAppContainer
import com.leepy0.photocull.update.CancellableUpdateDownload
import com.leepy0.photocull.update.UpdateController
import com.leepy0.photocull.update.UpdateControllerImpl
import com.leepy0.photocull.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 업데이트 바텀시트. 앱 최상위에 항상 놓인다(AppNavHost).
 * - [visible]이면 업데이트 상태(확인/받기/설치). 열릴 때 확인이 필요하면 바로 확인한다.
 * - 업데이트 직후 첫 실행이면 visible과 상관없이 "vX로 업데이트되었습니다" + 변경 내용을 한 번 보여준다.
 * - 지난 실행이 비정상 종료됐으면 한 번 알리고 오류 내용을 복사할 수 있게 한다.
 * - 앱 시작 3초 뒤 자동 확인(설정이 켜져 있고 마지막 확인 후 6시간이 지났을 때만, 실패는 조용히).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheetHost(visible: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val controller = LocalAppContainer.current.update
    val state by controller.state.collectAsStateWithLifecycle()
    val justUpdated by controller.justUpdated.collectAsStateWithLifecycle()
    val notesFlow: StateFlow<String?> = remember(controller) {
        (controller as? UpdateControllerImpl)?.justUpdatedNotes ?: MutableStateFlow(null)
    }
    val justUpdatedNotes by notesFlow.collectAsStateWithLifecycle()

    val impl = controller as? UpdateControllerImpl
    var canInstall by remember { mutableStateOf(controller.canInstallPackages()) }
    // 앱이 보이는지 컨트롤러에 알린다: 돌아오면 미뤄 둔 설치 확인 화면·끊긴 받기를 잇고,
    // 설정에서 '설치 허용'을 켜고 돌아왔으면 '권한 없음'을 걷어 바로 [설치]가 보이게 한다.
    LifecycleResumeEffect(controller) {
        canInstall = controller.canInstallPackages()
        impl?.onAppResumed()
        onPauseOrDispose { impl?.onAppPaused() }
    }

    // 가져오는 중에는 [설치]를 끄고 안내, 끝나면 저절로 다시 켠다
    val importController = LocalAppContainer.current.importController
    val importState by importController.state.collectAsStateWithLifecycle()
    val importing = remember(importState, importController) { UpdateControllerImpl.isImportBusy(importController) }
    LaunchedEffect(importing) { if (!importing) impl?.refreshBlockedFailure() }

    var crashAt by remember { mutableStateOf<Long?>(null) }
    var crashPending by remember { mutableStateOf(false) }
    var crashCopied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val unseen = withContext(Dispatchers.IO) {
            runCatching { if (CrashReporter.hasUnseenCrash(context)) CrashReporter.lastCrashAt(context) ?: 0L else null }.getOrNull()
        }
        if (unseen != null) {
            crashAt = unseen.takeIf { it > 0 }
            crashPending = true
        }
    }

    LaunchedEffect(controller) {
        delay(AUTO_CHECK_DELAY_MS)
        controller.check(userInitiated = false)
    }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        canInstall = controller.canInstallPackages()
        when (controller.state.value) {
            // 새 버전을 이미 알고 있어도(받기/설치 대기) 더 새 핫픽스가 있을 수 있어 조용히 다시 확인한다
            UpdateState.Idle, is UpdateState.UpToDate, is UpdateState.Failed,
            is UpdateState.Available, is UpdateState.ReadyToInstall,
            -> controller.check(userInitiated = true)
            else -> Unit
        }
    }

    val mode: UpdateSheetMode? = when {
        visible -> UpdateSheetMode.Update(
            state = state,
            currentVersionName = controller.currentVersionName,
            canInstall = canInstall,
            localBuild = controller.channel == CHANNEL_LOCAL,
            canCancelDownload = controller is CancellableUpdateDownload,
            failureAction = failureActionFor(context, state),
            importRunning = importing,
        )
        justUpdated != null -> UpdateSheetMode.JustUpdated(
            versionName = controller.currentVersionName,
            change = justUpdated.orEmpty(),
            notes = justUpdatedNotes,
        )
        crashPending -> UpdateSheetMode.Crash(crashedAt = crashAt, copied = crashCopied)
        else -> null
    }
    if (mode == null) return
    // 모드가 바뀌면(업데이트 → 업데이트 직후 안내 등) 시트를 새로 띄운다.
    key(mode::class) { ModeSheet(mode, controller, state, onDismiss, onCrashSeen = { crashPending = false }, onCrashCopied = { crashCopied = true }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSheet(
    mode: UpdateSheetMode,
    controller: UpdateController,
    state: UpdateState,
    onDismiss: () -> Unit,
    onCrashSeen: () -> Unit,
    onCrashCopied: () -> Unit,
) {
    val context = LocalContext.current
    val appScope = LocalAppContainer.current.appScope
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val finish: () -> Unit = {
        when (mode) {
            is UpdateSheetMode.Update -> onDismiss()
            is UpdateSheetMode.JustUpdated -> controller.consumeJustUpdated()
            is UpdateSheetMode.Crash -> {
                appScope.launch(Dispatchers.IO) { CrashReporter.markSeen(context) }
                onCrashSeen()
            }
        }
    }
    val closeAnimated: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { finish() }
    }

    val actions = UpdateSheetActions(
        onDownload = controller::download,
        onCancelDownload = { (controller as? CancellableUpdateDownload)?.cancelDownload() },
        onInstall = controller::install,
        onOpenInstallSettings = { openInstallSettings(context, controller) },
        onRetry = { retry(controller) },
        onOpenReleasePage = { openUrl(context, releasePageFor(controller, state)) },
        onClose = closeAnimated,
        onCopyCrash = {
            scope.launch {
                val report = withContext(Dispatchers.IO) { CrashReporter.lastCrash(context) }
                if (report != null && copyToClipboard(context, context.getString(R.string.update_crash_clip_label), report)) {
                    onCrashCopied()
                }
            }
        },
    )

    ModalBottomSheet(
        onDismissRequest = finish,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        UpdateSheetContent(mode = mode, actions = actions)
    }
}

private const val AUTO_CHECK_DELAY_MS = UpdateControllerImpl.AUTO_CHECK_DELAY_MS
private const val CHANNEL_LOCAL = "local"

/**
 * 실패 화면의 주요 행동. 문구는 모두 이 앱의 리소스라 원인을 문구로 구분할 수 있다.
 * 권한 없음 → 설정으로, 서명·호환 문제(앱 안에서 해결 불가) → 릴리스 페이지, 나머지 → 다시 시도.
 */
private fun failureActionFor(context: Context, state: UpdateState): FailureAction {
    val failed = state as? UpdateState.Failed ?: return FailureAction.RETRY
    val first = failed.message.lineSequence().firstOrNull().orEmpty()
    fun firstLineOf(res: Int) = context.getString(res).lineSequence().first()
    return when (first) {
        firstLineOf(R.string.update_err_no_permission) -> FailureAction.OPEN_INSTALL_SETTINGS
        firstLineOf(R.string.update_err_signature),
        firstLineOf(R.string.update_err_conflict),
        firstLineOf(R.string.update_err_downgrade),
        firstLineOf(R.string.update_err_incompatible),
        -> FailureAction.OPEN_RELEASE_PAGE
        else -> FailureAction.RETRY
    }
}

/** 실패 후 다시 시도: 실패 직전 단계(확인/받기/설치)를 다시 한다. */
private fun retry(controller: UpdateController) {
    controller.dismissError()
    when (controller.state.value) {
        is UpdateState.Available -> controller.download()
        is UpdateState.ReadyToInstall -> if (controller.canInstallPackages()) controller.install()
        UpdateState.Idle, is UpdateState.UpToDate -> controller.check(userInitiated = true)
        else -> Unit
    }
}

private fun releasePageFor(controller: UpdateController, state: UpdateState): String = when (state) {
    is UpdateState.Available -> state.info.pageUrl
    is UpdateState.Downloading -> state.info.pageUrl
    is UpdateState.ReadyToInstall -> state.info.pageUrl
    is UpdateState.Installing -> state.info.pageUrl
    is UpdateState.Failed -> state.info?.pageUrl ?: controller.releasesPageUrl
    else -> controller.releasesPageUrl
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun openInstallSettings(context: Context, controller: UpdateController) {
    val opened = runCatching {
        context.startActivity(controller.installPermissionSettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!opened) {
        // 앱별 화면이 없는 기기: 전체 목록 화면
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String): Boolean = runCatching {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}.isSuccess
