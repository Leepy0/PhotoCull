package com.leepy0.photocull.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import com.leepy0.photocull.R
import java.io.File

/** 실패 문구(문자열 리소스). message = 무엇이·왜(첫 줄이 제목), fix = 해결 방법 */
data class FailureText(@StringRes val message: Int, @StringRes val fix: Int?)

/**
 * PackageInstaller 세션으로 APK를 설치한다. 결과는 [InstallResultReceiver]로 온다.
 * UPDATE_PACKAGES_WITHOUT_USER_ACTION 권한 + USER_ACTION_NOT_REQUIRED로 가능하면 확인 화면 없이 설치된다.
 */
class ApkInstaller(private val context: Context) {

    /** 세션을 만들어 APK를 쓰고 커밋한다. 커밋까지 못 하면 false(세션은 버림). IO 스레드에서 호출. */
    fun install(apk: File, info: UpdateInfo): Boolean {
        val installer = context.packageManager.packageInstaller
        // 이전 시도에서 남은 세션 정리
        runCatching { installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } } }

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        var sessionId = -1
        return try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("PhotoCull.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                InstallMarker.mark(context, info.versionCode)
                session.commit(resultSender(sessionId, info))
            }
            true
        } catch (e: Exception) {
            // IOException(공간 부족 등), SecurityException(세션 권한) 등
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            InstallMarker.clear(context)
            false
        }
    }

    private fun resultSender(sessionId: Int, info: UpdateInfo) = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(context, InstallResultReceiver::class.java)
            .setPackage(context.packageName)
            .putExtra(InstallResultReceiver.EXTRA_VERSION_NAME, info.versionName),
        // 시스템이 EXTRA_STATUS 등을 채워 넣어야 하므로 MUTABLE(명시적 인텐트라 허용됨)
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    ).intentSender
}

/** PackageInstaller 상태 코드 → 사용자 문구 */
object InstallStatusMessages {
    private val SIGNATURE_HINTS = listOf("signature", "certificate", "INCONSISTENT_CERTIFICATES", "UPDATE_INCOMPATIBLE")

    fun forStatus(status: Int, statusMessage: String?): FailureText {
        val msg = statusMessage.orEmpty()
        val signature = SIGNATURE_HINTS.any { msg.contains(it, ignoreCase = true) }
        return when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> FailureText(R.string.update_err_aborted, R.string.update_fix_aborted)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> FailureText(R.string.update_err_blocked, R.string.update_fix_blocked)
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                if (signature) FailureText(R.string.update_err_signature, R.string.update_fix_reinstall)
                else FailureText(R.string.update_err_conflict, R.string.update_fix_reinstall)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> when {
                signature -> FailureText(R.string.update_err_signature, R.string.update_fix_reinstall)
                msg.contains("DOWNGRADE", ignoreCase = true) -> FailureText(R.string.update_err_downgrade, R.string.update_fix_release_page)
                else -> FailureText(R.string.update_err_incompatible, R.string.update_fix_release_page)
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> FailureText(R.string.update_err_storage, R.string.update_fix_storage)
            PackageInstaller.STATUS_FAILURE_INVALID -> FailureText(R.string.update_err_invalid_apk, R.string.update_fix_redownload)
            STATUS_FAILURE_TIMEOUT -> FailureText(R.string.update_err_timeout, R.string.update_fix_retry)
            else -> FailureText(R.string.update_err_install_generic, R.string.update_fix_install_generic)
        }
    }

    /** PackageInstaller.STATUS_FAILURE_TIMEOUT(API 34) */
    private const val STATUS_FAILURE_TIMEOUT = 8
}

/**
 * 이 앱이 설치를 시작했다는 표시. 설치 직후 [PackageReplacedReceiver]가 직접 시작한 업데이트인지 구분하는 데 쓴다
 * (브라우저 등으로 직접 설치한 경우에는 앱을 다시 띄우지 않는다).
 */
object InstallMarker {
    private const val PREFS = "update_install"
    private const val KEY_CODE = "pending_code"
    private const val KEY_AT = "pending_at"
    private const val FRESH_MS = 15 * 60 * 1000L

    fun mark(context: Context, versionCode: Int, now: Long = System.currentTimeMillis()) {
        prefs(context).edit().putInt(KEY_CODE, versionCode).putLong(KEY_AT, now).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** 최근에 [versionCode] 설치를 시작했으면 true. 읽은 뒤 지운다. */
    fun consume(context: Context, versionCode: Int, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(context)
        val code = p.getInt(KEY_CODE, 0)
        val at = p.getLong(KEY_AT, 0)
        p.edit().clear().apply()
        return code == versionCode && now - at in 0..FRESH_MS
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
