package com.leepy0.photocull.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.leepy0.photocull.R
import java.io.File

/** 실패 문구(문자열 리소스). message = 무엇이·왜(첫 줄이 제목), fix = 해결 방법 */
data class FailureText(@StringRes val message: Int, @StringRes val fix: Int?)

/**
 * 검증된 APK를 Android 시스템 설치 화면으로 전달한다.
 * PackageInstaller 세션을 직접 커밋하거나 사용자 확인 생략을 요청하지 않는다.
 */
class ApkInstaller(private val context: Context) {

    /** 시스템 설치 화면을 연다. 호출 실패 시 false를 반환한다. IO 스레드에서 호출. */
    fun install(apk: File, info: UpdateInfo): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                apk,
            )
            val confirm = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // 설치 완료 후 앱이 직접 시작한 업데이트인지 구분한다.
            InstallMarker.mark(context, info.versionCode)
            try {
                context.startActivity(confirm)
                true
            } catch (e: Exception) {
                InstallMarker.clear(context)
                false
            }
        } catch (e: Exception) {
            InstallMarker.clear(context)
            false
        }
    }
}

/** PackageInstaller 상태 코드 → 사용자 문구 (레거시 결과 처리 코드 호환용) */
object InstallStatusMessages {
    private val SIGNATURE_HINTS = listOf("signature", "certificate", "INCONSISTENT_CERTIFICATES", "UPDATE_INCOMPATIBLE")

    fun forStatus(status: Int, statusMessage: String?): FailureText {
        val msg = statusMessage.orEmpty()
        val signature = SIGNATURE_HINTS.any { msg.contains(it, ignoreCase = true) }
        return when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> FailureText(R.string.update_err_aborted, R.string.update_fix_aborted)
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                FailureText(R.string.update_err_blocked, R.string.update_fix_blocked)
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                if (signature) FailureText(R.string.update_err_signature, R.string.update_fix_reinstall)
                else FailureText(R.string.update_err_conflict, R.string.update_fix_reinstall)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> when {
                signature -> FailureText(R.string.update_err_signature, R.string.update_fix_reinstall)
                msg.contains("DOWNGRADE", ignoreCase = true) ->
                    FailureText(R.string.update_err_downgrade, R.string.update_fix_release_page)
                else -> FailureText(R.string.update_err_incompatible, R.string.update_fix_release_page)
            }
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                FailureText(R.string.update_err_storage, R.string.update_fix_storage)
            PackageInstaller.STATUS_FAILURE_INVALID ->
                FailureText(R.string.update_err_invalid_apk, R.string.update_fix_redownload)
            STATUS_FAILURE_TIMEOUT -> FailureText(R.string.update_err_timeout, R.string.update_fix_retry)
            else -> FailureText(R.string.update_err_install_generic, R.string.update_fix_install_generic)
        }
    }

    /** PackageInstaller.STATUS_FAILURE_TIMEOUT(API 34) */
    private const val STATUS_FAILURE_TIMEOUT = 8
}

/**
 * 이 앱이 설치를 시작했다는 표시. 설치 직후 [PackageReplacedReceiver]가 직접 시작한 업데이트인지 구분한다.
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
