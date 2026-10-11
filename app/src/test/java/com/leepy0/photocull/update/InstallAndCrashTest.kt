package com.leepy0.photocull.update

import android.content.Context
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.BuildConfig
import com.leepy0.photocull.diagnostics.CrashReporter
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** 설치 결과 코드 → 문구, 비정상 종료 기록 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class InstallAndCrashTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun text(status: Int, message: String? = null): Pair<String, String?> {
        val t = InstallStatusMessages.forStatus(status, message)
        return context.getString(t.message).lines().first() to t.fix?.let(context::getString)
    }

    // ---- 설치 상태 코드 ----

    @Test fun signatureMismatchIsExplained() {
        val (msg, fix) = text(PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match")
        assertThat(msg).isEqualTo("서명이 달라 업데이트할 수 없습니다")
        assertThat(fix).startsWith("기존 앱을 삭제하고 새 버전을 설치하세요")
        assertThat(text(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, "INSTALL_FAILED_INCONSISTENT_CERTIFICATES").first)
            .isEqualTo("서명이 달라 업데이트할 수 없습니다")
    }

    @Test fun otherStatusesHaveSpecificMessages() {
        assertThat(text(PackageInstaller.STATUS_FAILURE_STORAGE).first).contains("공간이 부족")
        assertThat(text(PackageInstaller.STATUS_FAILURE_ABORTED).first).isEqualTo("설치를 취소했습니다")
        assertThat(text(PackageInstaller.STATUS_FAILURE_CONFLICT, "other").first).contains("충돌")
        assertThat(text(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, "INSTALL_FAILED_VERSION_DOWNGRADE").first).contains("낮은 버전")
        assertThat(text(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, null).first).contains("호환되지 않는")
        assertThat(text(PackageInstaller.STATUS_FAILURE_INVALID).first).contains("올바르지 않습니다")
        assertThat(text(PackageInstaller.STATUS_FAILURE_BLOCKED).first).contains("막았습니다")
        assertThat(text(8).first).contains("시간이 초과")
        val (generic, fix) = text(PackageInstaller.STATUS_FAILURE, "weird internal text")
        assertThat(generic).isEqualTo("업데이트를 설치하지 못했습니다")
        assertThat(fix).contains("릴리스 페이지")
        assertThat(generic + fix).doesNotContain("weird")
    }

    @Test fun checkFailuresMapToKorean() {
        CheckFailure.entries.forEach { reason ->
            val t = UpdateControllerImpl.checkFailureText(reason)
            assertThat(context.getString(t.message)).isNotEmpty()
            assertThat(t.fix).isNotNull()
        }
    }

    @Test fun installMarkerOnlyMatchesFreshSameVersion() {
        InstallMarker.mark(context, 200, now = 1_000)
        assertThat(InstallMarker.consume(context, 200, now = 2_000)).isTrue()
        assertThat(InstallMarker.consume(context, 200, now = 2_000)).isFalse() // 한 번만
        InstallMarker.mark(context, 200, now = 1_000)
        assertThat(InstallMarker.consume(context, 300, now = 2_000)).isFalse()
        InstallMarker.mark(context, 200, now = 1_000)
        assertThat(InstallMarker.consume(context, 200, now = 1_000 + 60 * 60 * 1000L)).isFalse()
    }

    // ---- 비정상 종료 기록 ----

    @Before fun clearCrash() = CrashReporter.clear(context)

    @Test fun handlerWritesFullReportAndChainsToPreviousHandler() {
        var forwarded: Throwable? = null
        val handler = CrashReporter.createHandler(CrashReporter.crashDir(context)) { _, e -> forwarded = e }
        val error = IllegalStateException("outer boom", IOException("inner cause"))
        handler.uncaughtException(Thread("worker-7"), error)

        assertThat(forwarded).isSameInstanceAs(error)
        val report = CrashReporter.lastCrash(context)!!
        assertThat(report).contains("time: ")
        assertThat(report).contains("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_CHANNEL} · ${BuildConfig.GIT_SHA}")
        assertThat(report).contains("device: ")
        assertThat(report).contains("Android ")
        assertThat(report).contains("thread: worker-7")
        assertThat(report).contains("java.lang.IllegalStateException: outer boom")
        assertThat(report).contains("Caused by: java.io.IOException: inner cause")
        assertThat(report).contains("at com.leepy0.photocull.update.InstallAndCrashTest")
        assertThat(File(CrashReporter.crashDir(context), "last_crash.txt.tmp").exists()).isFalse()
        assertThat(CrashReporter.lastCrashAt(context)).isNotNull()
    }

    @Test fun crashNoticeShownOncePerCrash() {
        assertThat(CrashReporter.hasUnseenCrash(context)).isFalse()
        CrashReporter.write(CrashReporter.crashDir(context), "report 1")
        assertThat(CrashReporter.hasUnseenCrash(context)).isTrue()
        CrashReporter.markSeen(context)
        assertThat(CrashReporter.hasUnseenCrash(context)).isFalse()
        // 본 뒤에도 설정 화면에서 다시 볼 수 있다.
        assertThat(CrashReporter.lastCrash(context)).isEqualTo("report 1")

        CrashReporter.write(CrashReporter.crashDir(context), "report 2, longer")
        assertThat(CrashReporter.hasUnseenCrash(context)).isTrue()

        CrashReporter.clear(context)
        assertThat(CrashReporter.lastCrash(context)).isNull()
        assertThat(CrashReporter.hasUnseenCrash(context)).isFalse()
    }

    @Test fun hugeReportIsTruncated() {
        var deep: Throwable = RuntimeException("root")
        repeat(400) { deep = RuntimeException("level $it ".repeat(100), deep) }
        val report = CrashReporter.buildReport("main", deep, 0)
        assertThat(report.length).isAtMost(256 * 1024 + 32)
        assertThat(report).startsWith("PhotoCull crash report")
    }

    @Test fun reporterIsInstalledByApplication() {
        // PhotoCullApp.onCreate에서 설치된다(Robolectric도 같은 Application을 만든다).
        CrashReporter.install(context) // 두 번 불러도 안전
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isNotNull()
    }
}
