package com.leepy0.photocull.update

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.StringRes
import com.leepy0.photocull.R
import java.io.File

/** 실패 문구(문자열 리소스). message = 무엇이·왜(첫 줄이 제목), fix = 해결 방법 */
data class FailureText(@StringRes val message: Int, @StringRes val fix: Int?)

/**
 * 검증된 APK를 다운로드 폴더에 두고 시스템 다운로드 목록을 연다. 설치는 사용자가 거기서 APK를 눌러 직접 한다.
 * 앱은 설치 권한(REQUEST_INSTALL_PACKAGES)이 없고 설치를 시작하지 않는다.
 */
class ApkInstaller(private val context: Context) {

    /** 다운로드 폴더에 APK를 복사하고 다운로드 목록을 연다. 실패하면 false. IO 스레드에서 호출. */
    fun install(apk: File, info: UpdateInfo): Boolean {
        return try {
            removePublished(context)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "${ApkDownloader.PREFIX}${ApkDownloader.safeVersion(info.versionName)}.apk")
                put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            try {
                resolver.openOutputStream(uri)!!.use { out -> apk.inputStream().use { it.copyTo(out, 64 * 1024) } }
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                return false
            }
            // 설치 완료 후 앱이 직접 시작한 업데이트인지 구분한다.
            InstallMarker.mark(context, info.versionCode)
            val open = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(open)
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

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"

        /** 이 앱이 다운로드 폴더에 둔 업데이트 APK를 지운다(앱이 만든 항목이라 권한 없이 지울 수 있다). */
        fun removePublished(context: Context) {
            runCatching {
                context.contentResolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.MIME_TYPE} = ?",
                    arrayOf("${ApkDownloader.PREFIX}%.apk", APK_MIME),
                )
            }
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
