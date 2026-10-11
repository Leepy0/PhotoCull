package com.leepy0.photocull.importer

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.AppContainer
import com.leepy0.photocull.PhotoCullApp
import com.leepy0.photocull.analysis.AnalysisController
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.data.review.ReviewCounts
import com.leepy0.photocull.data.review.ReviewRepository
import com.leepy0.photocull.data.settings.SettingsRepository
import com.leepy0.photocull.notify.NotificationChannels
import com.leepy0.photocull.storage.CardCounts
import com.leepy0.photocull.storage.CardMonitor
import com.leepy0.photocull.storage.CardNotifications
import com.leepy0.photocull.storage.CardSource
import com.leepy0.photocull.storage.CardState
import com.leepy0.photocull.storage.CardVolume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowPowerManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 서비스 수명: 분석을 기다리는 중의 새 시작, 복사 중 중복 시작, 멈춘 뒤 알림, wake lock.
 * 앱 컨테이너의 가져오기 컨트롤러·분석을 테스트용(임시 폴더 카드, 끝나지 않는 분석)으로 바꿔 끼운다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ImportServiceLifecycleTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private lateinit var h: EngineHarness
    private lateinit var scope: CoroutineScope
    private lateinit var analysis: SlowAnalysis
    private lateinit var env: Env
    private lateinit var controller: ImportControllerImpl
    private lateinit var original: AppContainer
    private lateinit var nm: ShadowNotificationManager

    /** 테스트가 [idle]을 true로 바꿀 때까지 끝나지 않는 분석 */
    private class SlowAnalysis : AnalysisController {
        val idle = MutableStateFlow(false)
        override val status = MutableStateFlow(AnalysisStatus(running = true, done = 0, total = 3))
        override fun kick() {}
        override fun regroup() {}
        override suspend fun awaitIdle() {
            idle.first { it }
        }
    }

    private class FakeCardMonitor : CardMonitor {
        override val state: StateFlow<CardState> = MutableStateFlow(CardState.NotConnected)
        override fun refresh() {}
        override fun createAccessIntent(volumeId: String): Intent? = null
        override fun onAccessGranted(volumeId: String, treeUri: Uri) {}
    }

    private inner class Env : ImportEnvironment {
        val serviceStarts = ArrayList<Boolean>()
        override val storageRoot: File get() = h.phone
        override val destinationFiles: DestinationFiles = DestinationFiles.Default
        override val mediaScanner: MediaScanSink = MediaScanSink.None
        override val removalGraceMs: Long = 0
        override fun freeBytes(): Long = Long.MAX_VALUE / 2
        override suspend fun currentCard(): CardState = CardState.Ready(h.volume)
        override fun openSource(volume: CardVolume): CardSource = h.card
        override fun startService(moveMode: Boolean) {
            serviceStarts += moveMode
        }
    }

    private class TestContainer(
        real: AppContainer,
        override val importController: ImportController,
        override val analysis: AnalysisController,
        override val review: ReviewRepository,
    ) : AppContainer by real

    @Before fun setUp() {
        ShadowEnvironment.setExternalStorageDirectory(tmp.newFolder("emulated0").toPath())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        h = EngineHarness(tmp.root)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        analysis = SlowAnalysis()
        env = Env()
        val settings = SettingsRepository(app)
        controller = ImportControllerImpl(app, scope, h.db, settings, FakeCardMonitor(), analysis, env)
        runBlocking { controller.startupRecovery.join() }
        original = (app as PhotoCullApp).container
        setContainer(TestContainer(original, controller, analysis, ReviewRepository(h.db, settings)))
        h.card.put("DCIM/100CANON/IMG_0001.JPG", randomBytes(1, 20_000), DEFAULT_MTIME)
    }

    @After fun tearDown() {
        setContainer(original)
        scope.cancel()
        h.close()
    }

    private fun setContainer(container: AppContainer) {
        PhotoCullApp::class.java.getDeclaredField("container").apply { isAccessible = true }.set(app, container)
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        error("timed out waiting for $what")
    }

    private fun progressTitle(): String? =
        nm.getNotification(NotificationChannels.ID_IMPORT_PROGRESS)?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun finishedSession(): Long? = (controller.state.value as? ImportState.Finished)?.result?.sessionId

    private fun startService(intent: Intent): ServiceController<ImportService> =
        Robolectric.buildService(ImportService::class.java, intent).create().startCommand(0, 1)

    @Test fun startWhileWaitingForAnalysisRunsTheNewImport() {
        val service = startService(ImportService.startIntent(app, false))
        waitUntil("first import") { finishedSession() != null }
        val firstSession = finishedSession()
        waitUntil("analysis wait") { progressTitle() == "사진 분석 중" }
        // 분석을 기다리는 동안에도 CPU를 깨워 둔다(화면이 꺼져도 분석이 진행되게)
        assertThat(ShadowPowerManager.getLatestWakeLock()?.isHeld).isTrue()

        // 화면에서 새 사진을 다시 가져오기: 컨트롤러는 Finished라 시작할 수 있다
        h.card.put("DCIM/100CANON/IMG_0002.JPG", randomBytes(2, 20_000), DEFAULT_MTIME + 60_000)
        controller.scan()
        waitUntil("ready") { controller.state.value is ImportState.Ready }
        controller.start(moveMode = false)
        assertThat(controller.state.value).isInstanceOf(ImportState.Running::class.java)
        assertThat(env.serviceStarts).containsExactly(false)
        service.withIntent(ImportService.startIntent(app, false)).startCommand(0, 2)

        // 앞 작업은 분석 대기를 끝내고, 새 가져오기가 실제로 돈다(Running에 갇히지 않음)
        waitUntil("second import") { finishedSession().let { it != null && it != firstSession } }
        assertThat((controller.state.value as ImportState.Finished).result.savedCount).isEqualTo(1)
        assertThat(h.phoneFiles().keys).containsExactly("IMG_0001.JPG", "IMG_0002.JPG")
        // 앞 작업이 끝나면서 서비스를 멈추지 않았다(새 작업이 분석을 기다리는 중)
        waitUntil("second analysis wait") { progressTitle() == "사진 분석 중" }
        assertThat(shadowOf(service.get()).isStoppedBySelf).isFalse()

        analysis.idle.value = true
        waitUntil("service stop") { shadowOf(service.get()).isStoppedBySelf }
        assertThat(ShadowPowerManager.getLatestWakeLock()?.isHeld ?: false).isFalse()
        assertThat(nm.getNotification(NotificationChannels.ID_IMPORT_RESULT)).isNotNull()
        service.destroy()
        assertThat(nm.getNotification(NotificationChannels.ID_IMPORT_PROGRESS)).isNull()
    }

    @Test fun cardImportTapWhileCopyingIsLeftToRunningImportAndClearsCardNotification() {
        val reading = CountDownLatch(1)
        val gate = CountDownLatch(1)
        h.card.onRead = { _, _ ->
            reading.countDown()
            gate.await(10, TimeUnit.SECONDS)
        }
        val service = startService(ImportService.startFromCardIntent(app))
        waitUntil("copying") { reading.count == 0L }

        // 복사 중에 카드 알림의 [가져오기]를 누름: 진행 중인 가져오기가 맡고, 카드 알림은 지운다
        CardNotifications.showCameraCard(app, "EOS_DIGITAL", CardCounts(photos = 1, videos = 0, newFiles = 1))
        assertThat(nm.getNotification(NotificationChannels.ID_CARD)).isNotNull()
        service.withIntent(ImportService.startFromCardIntent(app)).startCommand(0, 2)
        assertThat(nm.getNotification(NotificationChannels.ID_CARD)).isNull()
        assertThat(controller.state.value).isInstanceOf(ImportState.Running::class.java)

        gate.countDown()
        analysis.idle.value = true
        waitUntil("finished") { controller.state.value is ImportState.Finished }
        waitUntil("service stop") { shadowOf(service.get()).isStoppedBySelf }
        // 한 번만 가져왔다
        assertThat((controller.state.value as ImportState.Finished).result.savedCount).isEqualTo(1)
        assertThat(h.phoneFiles()).hasSize(1)
    }

    @Test fun destroyClearsLeftoverProgressNotification() {
        analysis.idle.value = true
        val service = startService(ImportService.startIntent(app, false))
        waitUntil("service stop") { shadowOf(service.get()).isStoppedBySelf }
        // 멈춘 뒤 도착한 [취소]는 진행 알림을 다시 띄우지 않는다
        service.withIntent(ImportService.cancelIntent(app)).startCommand(0, 2)
        assertThat(nm.getNotification(NotificationChannels.ID_IMPORT_PROGRESS)).isNull()
        // 멈추는 사이 늦게 올라온 진행 알림이 있었어도 onDestroy가 지운다
        ImportNotifications(app).postProgress(ImportNotifications(app).cancelling())
        service.destroy()
        assertThat(nm.getNotification(NotificationChannels.ID_IMPORT_PROGRESS)).isNull()
    }

    @Test fun resultNotificationSaysCountsAreWhatIsLeftToReview() {
        val result = ImportResult(
            sessionId = 1, moveMode = true, savedCount = 3, duplicateCount = 0, failedCount = 0, sourceDeleteFailedCount = 0,
            savedBytes = 1, durationMs = 1, cancelled = false, failures = emptyList(), photoCount = 3, videoCount = 0,
            destDisplayPath = "DCIM/R10",
        )
        ImportNotifications(app).postResult(result, ReviewCounts(blur = 2, groups = 1))
        val n = nm.getNotification(NotificationChannels.ID_IMPORT_RESULT)
        assertThat(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("검토할 사진: 흐림 2장 · 연사 1그룹")
    }
}
