package com.leepy0.photocull.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.leepy0.photocull.PhotoCullApp
import com.leepy0.photocull.R

/**
 * PackageInstaller 세션 결과. exported=false라 시스템이 채운 우리 PendingIntent로만 온다.
 * - 사용자 확인 필요: 시스템 확인 화면을 띄운다.
 * - 성공: 할 일 없음(이 프로세스는 곧 종료되고 새 버전이 MY_PACKAGE_REPLACED를 받는다).
 * - 실패: 상태 코드를 한국어 문구로 바꿔 업데이트 상태에 반영한다.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val controller = runCatching { PhotoCullApp.container(context).update as? UpdateControllerImpl }.getOrNull()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                val started = confirm != null &&
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                if (started) {
                    controller?.onInstallAwaitingUser()
                } else {
                    InstallMarker.clear(context)
                    controller?.onInstallFailed(FailureText(R.string.update_err_confirm_ui, R.string.update_fix_confirm_ui))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                InstallMarker.clear(context)
                val text = InstallStatusMessages.forStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                controller?.onInstallFailed(text)
            }
        }
    }

    companion object {
        const val EXTRA_VERSION_NAME = "com.leepy0.photocull.update.VERSION_NAME"
    }
}
