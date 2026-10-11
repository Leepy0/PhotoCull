package com.leepy0.photocull.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import com.leepy0.photocull.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 비정상 종료 기록(DESIGN.md §1 #12). 처리되지 않은 예외를 filesDir/crash/last_crash.txt에 남기고
 * 원래 핸들러에 넘긴다. 다음 실행 때 업데이트 시트가 한 번 보여주고(복사 가능), 설정 화면에서 다시 볼 수 있다.
 * DI·코루틴에 의존하지 않는다(앱이 무너지는 중에도 동작해야 함).
 */
object CrashReporter {
    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"
    private const val PREFS = "crash_reporter"
    private const val KEY_SEEN = "seen_stamp"
    /** 보고서 최대 크기(긴 원인 사슬·재귀 스택 대비) */
    private const val MAX_CHARS = 256 * 1024

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val dir = crashDir(context.applicationContext ?: context)
            Thread.setDefaultUncaughtExceptionHandler(createHandler(dir, Thread.getDefaultUncaughtExceptionHandler()))
            installed = true
        }
    }

    /** 마지막 비정상 종료 보고서 전문. 없으면 null */
    fun lastCrash(context: Context): String? =
        runCatching { reportFile(context).takeIf { it.isFile }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 마지막 비정상 종료 시각(epoch ms). 없으면 null */
    fun lastCrashAt(context: Context): Long? =
        reportFile(context).takeIf { it.isFile }?.lastModified()?.takeIf { it > 0 }

    fun clear(context: Context) {
        runCatching { reportFile(context).delete() }
        runCatching { prefs(context).edit().remove(KEY_SEEN).apply() }
    }

    /** 아직 사용자에게 알리지 않은 보고서가 있으면 true */
    fun hasUnseenCrash(context: Context): Boolean {
        val stamp = stampOf(context) ?: return false
        return prefs(context).getString(KEY_SEEN, null) != stamp
    }

    /** 지금 보고서를 본 것으로 표시(한 번만 알리기 위함) */
    fun markSeen(context: Context) {
        val stamp = stampOf(context) ?: return
        prefs(context).edit().putString(KEY_SEEN, stamp).apply()
    }

    internal fun createHandler(dir: File, previous: Thread.UncaughtExceptionHandler?) =
        Thread.UncaughtExceptionHandler { thread, error ->
            try {
                write(dir, buildReport(thread.name, error, System.currentTimeMillis()))
            } catch (_: Throwable) {
                // 기록 실패가 원래 종료 처리를 막으면 안 된다.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }

    internal fun buildReport(threadName: String, error: Throwable, at: Long): String {
        val trace = StringWriter().also { sw -> PrintWriter(sw).use { error.printStackTrace(it) } }.toString()
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx", Locale.US)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(at))
        val report = buildString {
            appendLine("PhotoCull crash report")
            appendLine("time: $time")
            appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_CHANNEL} · ${BuildConfig.GIT_SHA}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("thread: $threadName")
            appendLine()
            append(trace)
        }
        return if (report.length <= MAX_CHARS) report else report.take(MAX_CHARS) + "\n… (truncated)"
    }

    /** 임시 파일에 쓰고 이름을 바꿔 반쯤 쓴 보고서가 남지 않게 한다. */
    internal fun write(dir: File, report: String) {
        dir.mkdirs()
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(report)
        val target = File(dir, FILE)
        if (!tmp.renameTo(target)) {
            target.writeText(report)
            tmp.delete()
        }
    }

    internal fun crashDir(context: Context): File = File(context.filesDir, DIR)

    private fun reportFile(context: Context): File = File(crashDir(context), FILE)

    private fun stampOf(context: Context): String? {
        val f = reportFile(context)
        if (!f.isFile) return null
        return "${f.lastModified()}:${f.length()}"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
