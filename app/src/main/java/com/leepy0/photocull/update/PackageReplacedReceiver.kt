package com.leepy0.photocull.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.leepy0.photocull.AppDestinations
import com.leepy0.photocull.BuildConfig

/**
 * 업데이트 설치 직후(새 버전 프로세스에서) 호출된다.
 * - 받아 둔 APK·임시 파일을 지운다.
 * - 앱이 직접 시작한 업데이트면 "업데이트 완료" 알림을 띄우고 앱을 다시 연다(시스템이 막으면 알림으로 연다).
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { ApkDownloader.cleanupDir(UpdateFiles.downloadDir(context), currentVersionCode = null) }
        ApkInstaller.removePublished(context)
        val startedByUs = runCatching { InstallMarker.consume(context, BuildConfig.VERSION_CODE) }.getOrDefault(false)
        if (startedByUs) {
            UpdateNotifications.notifyUpdated(context, BuildConfig.VERSION_NAME)
            // 백그라운드 액티비티 시작 제한에 걸리면 조용히 무시된다. 그때는 위 알림으로 연다.
            runCatching { context.startActivity(AppDestinations.intent(context, AppDestinations.HOME)) }
        } else {
            UpdateNotifications.cancel(context)
        }
    }
}
