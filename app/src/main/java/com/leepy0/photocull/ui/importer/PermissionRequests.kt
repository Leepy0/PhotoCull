package com.leepy0.photocull.ui.importer

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.leepy0.photocull.util.Permissions

/** 다른 앱 화면(설정 등)을 연다. 해당 화면이 없는 기기면 대체 인텐트, 그것도 없으면 false */
fun Context.startActivitySafely(intent: Intent, fallback: Intent? = null): Boolean {
    val candidates = listOfNotNull(intent, fallback)
    for (candidate in candidates) {
        try {
            startActivity(candidate)
            return true
        } catch (e: ActivityNotFoundException) {
            continue
        } catch (e: SecurityException) {
            continue
        }
    }
    return false
}

/** 모든 파일 접근 설정(앱별 화면, 없으면 전체 목록) */
fun Context.openAllFilesAccessSettings(): Boolean =
    startActivitySafely(Permissions.allFilesAccessIntent(this), Permissions.allFilesAccessFallbackIntent())

/** 저장공간 설정(카드 꺼내기·공간 확보 안내). 없으면 일반 설정 */
fun Context.openStorageSettings(): Boolean =
    startActivitySafely(Permissions.storageSettingsIntent(), Intent(android.provider.Settings.ACTION_SETTINGS))

/** 앱 알림 설정. 없으면 앱 정보 화면 */
fun Context.openNotificationSettings(): Boolean =
    startActivitySafely(
        Permissions.appNotificationSettingsIntent(this),
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )

/** 알림을 실제로 띄울 수 있는지(권한 + 앱 알림 스위치). Android 12 이하는 런타임 권한이 없다. */
fun notificationsAllowed(context: Context): Boolean {
    val enabled = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(true)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return enabled
    return enabled && Permissions.hasNotificationPermission(context)
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * 알림 권한 요청. 이미 '다시 묻지 않음'으로 막혀 있으면 시스템이 창 없이 바로 거부하므로,
 * 요청하는 동안 화면이 가려지지(ON_PAUSE) 않았으면 막힌 것으로 보고 [onBlocked]를 부른다.
 */
class NotificationPermissionRequester internal constructor(
    private val launch: () -> Unit,
) {
    fun request() = launch()
}

/**
 * @param onResult 요청 결과. blocked = 거부됐고 더는 물을 수 없음(다음부터는 설정에서 켜야 함)
 * @param onBlocked 창이 뜨지 않고 바로 거부됨(이미 막힘) 또는 런타임 권한이 아닌 이유로 꺼져 있음
 */
@Composable
fun rememberNotificationPermissionRequester(
    onResult: (granted: Boolean, blocked: Boolean) -> Unit = { _, _ -> },
    onBlocked: () -> Unit = {},
): NotificationPermissionRequester {
    val context = LocalContext.current
    val latestResult by rememberUpdatedState(onResult)
    val latestBlocked by rememberUpdatedState(onBlocked)
    var requesting by remember { mutableStateOf(false) }
    var dialogShown by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (requesting) dialogShown = true
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val shown = dialogShown
        requesting = false
        dialogShown = false
        val allowed = granted && notificationsAllowed(context)
        if (granted) {
            latestResult(allowed, false)
        } else {
            val activity = context.findActivity()
            val canAskAgain = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
            latestResult(false, !canAskAgain)
            if (!shown && !canAskAgain) latestBlocked()
        }
    }

    return remember(launcher) {
        NotificationPermissionRequester {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || Permissions.hasNotificationPermission(context)) {
                // 권한은 있는데 앱 알림이 꺼져 있거나(설정에서 끔) 런타임 권한이 없는 버전: 설정에서 켜야 한다
                latestBlocked()
            } else {
                requesting = true
                dialogShown = false
                try {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (e: ActivityNotFoundException) {
                    requesting = false
                    latestBlocked()
                }
            }
        }
    }
}

/**
 * 카드 폴더 승인(SAF) 화면을 열고, 승인되면 [onGranted](볼륨 id, 트리 URI).
 * 반환한 함수에 볼륨 id와 인텐트(CardMonitor.createAccessIntent)를 넘긴다. 인텐트가 없으면(카드가 빠짐) [onUnavailable].
 */
@Composable
fun rememberFolderAccessLauncher(
    onGranted: (volumeId: String, treeUri: Uri) -> Unit,
    onUnavailable: () -> Unit,
): (volumeId: String, intent: Intent?) -> Unit {
    val latestGranted by rememberUpdatedState(onGranted)
    val latestUnavailable by rememberUpdatedState(onUnavailable)
    // 승인 화면에 다녀오는 사이 액티비티가 다시 만들어질 수 있어 저장한다
    var pendingVolumeId by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val volumeId = pendingVolumeId
        pendingVolumeId = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && volumeId != null && uri != null) {
            latestGranted(volumeId, uri)
        } else {
            // 취소했거나 다른 폴더를 골랐음: 상태만 다시 확인
            latestUnavailable()
        }
    }
    return remember(launcher) {
        { volumeId: String, intent: Intent? ->
            if (intent == null) {
                latestUnavailable()
            } else {
                pendingVolumeId = volumeId
                try {
                    launcher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    pendingVolumeId = null
                    latestUnavailable()
                }
            }
        }
    }
}
