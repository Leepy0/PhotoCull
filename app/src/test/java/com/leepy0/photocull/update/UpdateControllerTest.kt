package com.leepy0.photocull.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.R
import com.leepy0.photocull.data.settings.SettingsRepository
import com.leepy0.photocull.update.TestReleases.API_URL
import com.leepy0.photocull.update.TestReleases.APK_URL
import com.leepy0.photocull.update.TestReleases.MANIFEST_URL
import com.leepy0.photocull.update.TestReleases.REPO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UpdateControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val http = FakeUpdateHttp()
    private val prefs = FakePrefs()
    private val scheduler = FakeScheduler()
    private var importRunning = false
    private val installed = mutableListOf<File>()
    private var now = 1_800_000_000_000L
    private val apkBytes = ByteArray(200_000) { (it % 97).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }
    private val downloadDir by lazy { File(context.cacheDir, "update-test") }

    @Before fun setUp() {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        downloadDir.deleteRecursively()
        UpdateFiles.notesFile(context).delete()
    }

    private val scopes = mutableListOf<CoroutineScope>()

    @After fun tearDown() = scopes.forEach { it.cancel() }

    /**
     * 컨트롤러 전용 스코프. backgroundScope 작업은 advanceUntilIdle이 기다리지 않으므로
     * 같은 스케줄러를 쓰는 별도 스코프를 쓴다(설정 구독처럼 끝나지 않는 작업이 있어 runTest 자식으로도 둘 수 없다).
     */
    private fun TestScope.controller(versionCode: Int = 100, versionName: String = "0.1.0", channel: String = "release") =
        UpdateControllerImpl(
            context = context,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it },
            settings = SettingsRepository(context),
            prefs = prefs,
            http = http,
            scheduler = scheduler,
            isImportRunning = { importRunning },
            startInstall = { apk, _ -> installed += apk; true },
            currentVersionName = versionName,
            currentVersionCode = versionCode,
            channel = channel,
            io = StandardTestDispatcher(testScheduler),
            clock = { now },
            repo = REPO,
            downloadDir = downloadDir,
            prompter = prompter,
        )

    private val prompter = FakePrompter()

    private fun serveRelease(sha: String = apkSha) {
        http.text(MANIFEST_URL, 200, TestReleases.manifest(sha = sha, size = apkBytes.size.toLong()))
        http.bytes(APK_URL, apkBytes)
    }

    // ---- 확인 ----

    @Test fun userCheckFailureShowsWhatWhyFixWithoutRawException() = runTest {
        http.networkError(MANIFEST_URL).networkError(API_URL)
        val c = controller()
        c.check(userInitiated = true)
        advanceUntilIdle()
        val s = c.state.value as UpdateState.Failed
        assertThat(s.message.lines().first()).isEqualTo("업데이트를 확인하지 못했습니다")
        assertThat(s.message.lines().size).isAtLeast(2)
        assertThat(s.fix).contains("네트워크")
        assertThat(s.message + s.fix).doesNotContain("fake")
        assertThat(s.info).isNull()
        assertThat(prefs.lastCheck).isEqualTo(0)
    }

    @Test fun backgroundCheckFailureIsSilent() = runTest {
        http.networkError(MANIFEST_URL).networkError(API_URL)
        val c = controller()
        c.check(userInitiated = false)
        advanceUntilIdle()
        assertThat(c.state.value).isEqualTo(UpdateState.Idle)
        assertThat(http.requests).isNotEmpty()
    }

    @Test fun autoCheckWaitsSixHoursAndHonorsSetting() = runTest {
        serveRelease()
        val c = controller()
        prefs.lastCheck = now - 60 * 60 * 1000L
        c.check(userInitiated = false)
        advanceUntilIdle()
        assertThat(http.requests).isEmpty()

        prefs.auto.value = false
        prefs.lastCheck = now - 7 * 60 * 60 * 1000L
        c.check(userInitiated = false)
        advanceUntilIdle()
        assertThat(http.requests).isEmpty()

        prefs.auto.value = true
        c.check(userInitiated = false)
        advanceUntilIdle()
        assertThat(http.requests).isNotEmpty()
        assertThat(c.state.value).isInstanceOf(UpdateState.Available::class.java)
        assertThat(prefs.lastCheck).isEqualTo(now)
    }

    @Test fun userCheckIgnoresIntervalAndAutoSetting() = runTest {
        prefs.auto.value = false
        prefs.lastCheck = now - 1000
        val c = controller()
        c.check(userInitiated = true)
        advanceUntilIdle()
        // 매니페스트·API 모두 404 = 릴리스 없음 = 최신
        assertThat(c.state.value).isEqualTo(UpdateState.UpToDate(now))
        assertThat(http.requests).containsExactly(MANIFEST_URL, API_URL).inOrder()
    }

    @Test fun backgroundCheckForWorkerReturnsResult() = runTest {
        serveRelease()
        val c = controller()
        val r = c.backgroundCheck()
        assertThat(r).isInstanceOf(CheckResult.Available::class.java)
        assertThat(c.state.value).isInstanceOf(UpdateState.Available::class.java)
    }

    // ---- 받기 ----

    @Test fun downloadVerifiesAndBecomesReadyToInstall() = runTest {
        serveRelease()
        val c = controller()
        c.check(true)
        advanceUntilIdle()
        c.download()
        assertThat(c.state.value).isInstanceOf(UpdateState.Downloading::class.java)
        advanceUntilIdle()
        val ready = c.state.value as UpdateState.ReadyToInstall
        assertThat(ready.info.versionName).isEqualTo("0.2.0")
        assertThat(File(downloadDir, "PhotoCull-0.2.0.apk").readBytes()).isEqualTo(apkBytes)
        // 설치 후 첫 실행에서 보여줄 변경 내용을 저장해 둔다.
        assertThat(UpdateFiles.readNotes(context, 200)).contains("흐림 판정 개선")
    }

    @Test fun corruptDownloadFailsAndRetryReturnsToAvailable() = runTest {
        serveRelease(sha = "e".repeat(64))
        val c = controller()
        c.check(true)
        advanceUntilIdle()
        c.download()
        advanceUntilIdle()
        val failed = c.state.value as UpdateState.Failed
        assertThat(failed.message.lines().first()).isEqualTo("다운로드한 파일이 손상되었습니다")
        assertThat(failed.fix).isEqualTo("다시 시도하세요.")
        assertThat(failed.info).isNotNull()
        assertThat(downloadDir.list().orEmpty().toList()).isEmpty()
        c.dismissError()
        assertThat(c.state.value).isInstanceOf(UpdateState.Available::class.java)
    }

    @Test fun missingShaIsRefusedWithoutDownloading() = runTest {
        http.text(MANIFEST_URL, 200, TestReleases.manifest(sha = null))
        http.bytes(APK_URL, apkBytes)
        val c = controller()
        c.check(true)
        advanceUntilIdle()
        http.requests.clear()
        c.download()
        val failed = c.state.value as UpdateState.Failed
        assertThat(failed.message).contains("SHA-256")
        assertThat(http.requests).isEmpty()
    }

    @Test fun cancelDownloadReturnsToAvailable() = runTest {
        serveRelease()
        val c = controller()
        c.check(true)
        advanceUntilIdle()
        c.download()
        c.cancelDownload()
        advanceUntilIdle()
        assertThat(c.state.value).isInstanceOf(UpdateState.Available::class.java)
        assertThat(downloadDir.list().orEmpty().filter { it.endsWith(".partial") }).isEmpty()
    }

    @Test fun alreadyDownloadedApkSkipsStraightToReady() = runTest {
        serveRelease()
        val first = controller()
        first.check(true)
        advanceUntilIdle()
        first.download()
        advanceUntilIdle()
        // 앱 재시작 후 다시 확인하면 받아 둔 파일을 쓴다.
        val second = controller()
        advanceUntilIdle()
        second.check(true)
        advanceUntilIdle()
        assertThat(second.state.value).isInstanceOf(UpdateState.ReadyToInstall::class.java)
    }

    // ---- 설치 ----

    @Test fun installRefusedWhileImporting() = runTest {
        val c = readyController()
        importRunning = true
        c.install()
        val s = c.state.value as UpdateState.Failed
        assertThat(s.fix).isEqualTo("가져오기가 끝난 뒤 설치하세요.")
        assertThat(s.info).isNotNull()
        assertThat(installed).isEmpty()
    }

    @Test fun installRequiresUnknownSourcesPermission() = runTest {
        val c = readyController()
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        assertThat(c.canInstallPackages()).isFalse()
        c.install()
        val s = c.state.value as UpdateState.Failed
        assertThat(s.message).isEqualTo(context.getString(R.string.update_err_no_permission))
        assertThat(s.fix).contains("설치 허용하러 가기")
        assertThat(c.installPermissionSettingsIntent().data.toString()).isEqualTo("package:${context.packageName}")
        // 설정에서 허용하고 돌아오면(화면 재개) 낡은 '권한 없음'을 걷어 바로 [설치] 단계로
        c.onAppResumed()
        assertThat(c.state.value).isInstanceOf(UpdateState.Failed::class.java)
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        c.onAppResumed()
        assertThat(c.state.value).isInstanceOf(UpdateState.ReadyToInstall::class.java)
    }

    @Test fun importBlockClearsByItselfWhenImportEnds() = runTest {
        val c = readyController()
        importRunning = true
        assertThat(c.isInstallBlockedByImport()).isTrue()
        c.install()
        assertThat(c.state.value).isInstanceOf(UpdateState.Failed::class.java)
        c.refreshBlockedFailure()
        assertThat(c.state.value).isInstanceOf(UpdateState.Failed::class.java)
        importRunning = false
        c.refreshBlockedFailure()
        assertThat(c.state.value).isInstanceOf(UpdateState.ReadyToInstall::class.java)
    }

    @Test fun otherFailuresAreNotClearedOnResume() = runTest {
        serveRelease(sha = "e".repeat(64))
        val c = controller()
        c.check(true)
        advanceUntilIdle()
        c.download()
        advanceUntilIdle()
        c.onAppResumed()
        assertThat(c.state.value).isInstanceOf(UpdateState.Failed::class.java)
    }

    // ---- 설치 확인 화면(앱이 가려져 있으면 시스템이 막는다) ----

    @Test fun confirmationWhileVisibleOpensRightAway() = runTest {
        val c = readyController()
        c.install()
        runCurrent()
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        assertThat(c.onConfirmationRequired(confirm, appInForeground = true)).isTrue()
        assertThat(prompter.launched).containsExactly(confirm)
        assertThat(prompter.notified).isEmpty()
        assertThat(c.state.value).isInstanceOf(UpdateState.Installing::class.java)
    }

    @Test fun confirmationInBackgroundWaitsForResumeAndPostsNotification() = runTest {
        val c = readyController()
        c.install()
        runCurrent()
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        assertThat(c.onConfirmationRequired(confirm, appInForeground = false)).isTrue()
        // 바로 띄우지 않고(조용히 막힘) 알림으로 열 수 있게
        assertThat(prompter.launched).isEmpty()
        assertThat(prompter.notified.single()).isEqualTo(confirm to "0.2.0")
        assertThat(c.state.value).isInstanceOf(UpdateState.Installing::class.java)

        c.onAppResumed()
        assertThat(prompter.launched).containsExactly(confirm)
        assertThat(prompter.cancelled).isEqualTo(1)
        // 한 번만
        c.onAppResumed()
        assertThat(prompter.launched).hasSize(1)
    }

    @Test fun confirmationThatCannotOpenIsAFailure() = runTest {
        val c = readyController()
        c.install()
        runCurrent()
        prompter.launchWorks = false
        assertThat(c.onConfirmationRequired(Intent("x"), appInForeground = true)).isFalse()
        val s = c.state.value as UpdateState.Failed
        assertThat(s.message).isEqualTo(context.getString(R.string.update_err_confirm_ui))
    }

    // ---- 백그라운드에서 끊긴 받기 ----

    @Test fun downloadCutWhileInBackgroundRetriesOnceWhenBack() = runTest {
        serveRelease()
        val c = controller()
        c.check(true)
        advanceUntilIdle()
        http.networkErrorOnce(APK_URL)
        c.download()
        c.onAppPaused()
        advanceUntilIdle()
        assertThat(c.state.value).isInstanceOf(UpdateState.Failed::class.java)

        c.onAppResumed()
        assertThat(c.state.value).isInstanceOf(UpdateState.Downloading::class.java)
        advanceUntilIdle()
        assertThat(c.state.value).isInstanceOf(UpdateState.ReadyToInstall::class.java)
    }

    @Test fun downloadCutAfterBeingBackgroundedRetriesImmediatelyIfAlreadyBack() = runTest {
        serveRelease()
        val c = controller()
        c.check(true)
        advanceUntilIdle()
        http.networkErrorOnce(APK_URL)
        c.download()
        c.onAppPaused()
        c.onAppResumed()
        advanceUntilIdle()
        assertThat(c.state.value).isInstanceOf(UpdateState.ReadyToInstall::class.java)
        assertThat(http.requests.count { it == APK_URL }).isEqualTo(2)
    }

    @Test fun autoRetryHappensOnlyOnceAndNotForForegroundFailures() = runTest {
        serveRelease()
        val c = controller()
        c.check(true)
        advanceUntilIdle()
        // 앱을 보고 있는 동안 끊김: 자동으로 다시 받지 않는다(사용자가 [다시 시도])
        http.networkErrorOnce(APK_URL)
        c.download()
        advanceUntilIdle()
        assertThat(c.state.value).isInstanceOf(UpdateState.Failed::class.java)
        c.onAppResumed()
        assertThat(c.state.value).isInstanceOf(UpdateState.Failed::class.java)

        // 백그라운드에서 두 번 끊김: 돌아왔을 때 한 번만 다시
        c.dismissError()
        http.networkErrorOnce(APK_URL).networkErrorOnce(APK_URL)
        c.download()
        c.onAppPaused()
        advanceUntilIdle()
        c.onAppResumed()
        advanceUntilIdle()
        assertThat(c.state.value).isInstanceOf(UpdateState.Failed::class.java)
        c.onAppPaused()
        c.onAppResumed()
        advanceUntilIdle()
        assertThat(c.state.value).isInstanceOf(UpdateState.Failed::class.java)
    }

    // ---- 확인이 삼켜지거나 막히지 않게 ----

    @Test fun userCheckDuringSkippedAutoCheckStillChecks() = runTest {
        serveRelease()
        prefs.lastCheck = now - 60 * 60 * 1000L // 6시간이 안 지나 자동 확인은 아무것도 안 하고 끝난다
        val c = controller()
        c.check(userInitiated = false)
        // 자동 확인이 도는 중에 사용자가 업데이트 시트를 열었다
        c.check(userInitiated = true)
        advanceUntilIdle()
        assertThat(c.state.value).isInstanceOf(UpdateState.Available::class.java)
        assertThat(http.requests).contains(MANIFEST_URL)
    }

    @Test fun knownVersionIsReplacedOnlyByANewerOne() = runTest {
        serveRelease()
        val c = controller()
        c.check(true)
        advanceUntilIdle()
        val first = c.state.value as UpdateState.Available
        assertThat(first.info.versionCode).isEqualTo(200)

        // 같은 버전 → 그대로(확인 중 화면으로 바꾸지도 않는다)
        c.check(true)
        assertThat(c.state.value).isEqualTo(first)
        advanceUntilIdle()
        assertThat(c.state.value).isEqualTo(first)

        // 확인 실패 → 알고 있던 새 버전을 그대로 보여준다
        http.networkError(MANIFEST_URL).networkError(API_URL)
        c.check(true)
        advanceUntilIdle()
        assertThat(c.state.value).isEqualTo(first)

        // 더 새 핫픽스 → 바꾼다
        http.text(MANIFEST_URL, 200, TestReleases.manifest(version = "0.2.1", versionCode = 201, sha = apkSha, size = apkBytes.size.toLong()))
        c.check(true)
        advanceUntilIdle()
        assertThat((c.state.value as UpdateState.Available).info.versionName).isEqualTo("0.2.1")
    }

    @Test fun readyToInstallStillLooksForAHotfix() = runTest {
        val c = readyController()
        http.text(MANIFEST_URL, 200, TestReleases.manifest(version = "0.2.1", versionCode = 201, sha = apkSha, size = apkBytes.size.toLong()))
        c.check(true)
        advanceUntilIdle()
        assertThat((c.state.value as UpdateState.Available).info.versionCode).isEqualTo(201)
    }

    @Test fun installCommitsSessionAndReceiverFailureIsShown() = runTest {
        val c = readyController()
        c.install()
        assertThat(c.state.value).isInstanceOf(UpdateState.Installing::class.java)
        runCurrent()
        assertThat(c.state.value).isInstanceOf(UpdateState.Installing::class.java)
        assertThat(installed.single().name).isEqualTo("PhotoCull-0.2.0.apk")
        c.onInstallFailed(InstallStatusMessages.forStatus(PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match"))
        val s = c.state.value as UpdateState.Failed
        assertThat(s.message.lines().first()).isEqualTo("서명이 달라 업데이트할 수 없습니다")
        assertThat(s.fix).startsWith("기존 앱을 삭제하고 새 버전을 설치하세요")
        assertThat(s.info?.versionName).isEqualTo("0.2.0")
    }

    @Test fun installWithoutResultFallsBackToReady() = runTest {
        val c = readyController()
        c.install()
        advanceUntilIdle()
        assertThat(c.state.value).isInstanceOf(UpdateState.ReadyToInstall::class.java)
    }

    private suspend fun TestScope.readyController(): UpdateControllerImpl {
        serveRelease()
        val c = controller()
        c.check(true)
        advanceUntilIdle()
        c.download()
        advanceUntilIdle()
        check(c.state.value is UpdateState.ReadyToInstall)
        return c
    }

    // ---- 업데이트 직후 ----

    @Test fun justUpdatedShownOnceWithCachedNotes() = runTest {
        UpdateFiles.writeNotes(context, info(versionCode = 200, notes = "- 새 기능"))
        prefs.lastRun = 100
        val c = controller(versionCode = 200, versionName = "0.2.0")
        advanceUntilIdle()
        assertThat(c.justUpdated.value).isEqualTo("v0.1.0 → v0.2.0")
        assertThat(c.justUpdatedNotes.value).isEqualTo("- 새 기능")
        // 안내를 닫기 전에는 저장하지 않는다(화면 없이 프로세스가 떠도 다음 실행에서 다시 보이게).
        assertThat(prefs.lastRun).isEqualTo(100)
        val beforeConsume = controller(versionCode = 200, versionName = "0.2.0")
        advanceUntilIdle()
        assertThat(beforeConsume.justUpdated.value).isEqualTo("v0.1.0 → v0.2.0")

        c.consumeJustUpdated()
        advanceUntilIdle()
        assertThat(c.justUpdated.value).isNull()
        assertThat(prefs.lastRun).isEqualTo(200)

        // 다음 실행에서는 보이지 않는다.
        val again = controller(versionCode = 200, versionName = "0.2.0")
        advanceUntilIdle()
        assertThat(again.justUpdated.value).isNull()
    }

    @Test fun firstInstallIsNotJustUpdatedAndNotesOfOtherVersionIgnored() = runTest {
        UpdateFiles.writeNotes(context, info(versionCode = 300, notes = "다른 버전"))
        val c = controller(versionCode = 200, versionName = "0.2.0")
        advanceUntilIdle()
        assertThat(c.justUpdated.value).isNull()
        assertThat(prefs.lastRun).isEqualTo(200)
        assertThat(UpdateFiles.readNotes(context, 200)).isNull()
    }

    @Test fun oldApksAreCleanedOnStart() = runTest {
        downloadDir.mkdirs()
        File(downloadDir, "PhotoCull-0.1.0.apk").writeText("old")
        File(downloadDir, "PhotoCull-0.2.0.apk.partial").writeText("half")
        controller(versionCode = 100)
        advanceUntilIdle()
        assertThat(downloadDir.list().orEmpty().toList()).isEmpty()
    }

    // ---- 백그라운드 예약 ----

    @Test fun periodicWorkFollowsSetting() = runTest {
        controller()
        advanceUntilIdle()
        assertThat(scheduler.scheduled).isEqualTo(1)
        prefs.auto.value = false
        advanceUntilIdle()
        assertThat(scheduler.cancelled).isEqualTo(1)
        prefs.auto.value = true
        advanceUntilIdle()
        assertThat(scheduler.scheduled).isEqualTo(2)
    }

    private fun info(versionCode: Int, notes: String) = UpdateInfo(
        versionName = Versions.nameOf(versionCode),
        versionCode = versionCode,
        apkUrl = APK_URL,
        sha256 = apkSha,
        sizeBytes = null,
        notes = notes,
        pageUrl = "https://github.com/$REPO/releases",
        publishedAt = null,
    )

    class FakePrefs : UpdatePrefsStore {
        val auto = MutableStateFlow(true)
        var lastCheck = 0L
        var lastRun = 0
        override val autoUpdateCheck = auto
        override suspend fun autoUpdateCheckEnabled() = auto.value
        override suspend fun lastCheckAt() = lastCheck
        override suspend fun setLastCheckAt(v: Long) { lastCheck = v }
        override suspend fun lastRunVersionCode() = lastRun
        override suspend fun setLastRunVersionCode(v: Int) { lastRun = v }
    }

    class FakePrompter : InstallPrompter {
        var launchWorks = true
        val launched = mutableListOf<Intent>()
        val notified = mutableListOf<Pair<Intent, String?>>()
        var cancelled = 0
        override fun launch(confirm: Intent): Boolean {
            if (launchWorks) launched += confirm
            return launchWorks
        }
        override fun notify(confirm: Intent, versionName: String?) { notified += confirm to versionName }
        override fun cancelNotification() { cancelled++ }
    }

    class FakeScheduler : UpdateScheduler {
        var scheduled = 0
        var cancelled = 0
        override fun schedule() { scheduled++ }
        override fun cancel() { cancelled++ }
    }
}
