package com.leepy0.photocull.importer

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.analysis.AnalysisController
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.data.db.ImportSessionEntity
import com.leepy0.photocull.data.db.ImportStatus
import com.leepy0.photocull.data.db.MediaItemEntity
import com.leepy0.photocull.data.db.MediaType
import com.leepy0.photocull.data.settings.SettingsRepository
import com.leepy0.photocull.storage.CardMonitor
import com.leepy0.photocull.storage.CardSource
import com.leepy0.photocull.storage.CardState
import com.leepy0.photocull.storage.CardVolume
import com.leepy0.photocull.storage.StoragePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ImportControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var h: EngineHarness
    private lateinit var scope: CoroutineScope
    private lateinit var monitor: FakeCardMonitor
    private lateinit var env: FakeEnv
    private lateinit var analysis: FakeAnalysis
    private lateinit var controller: ImportControllerImpl

    private class FakeCardMonitor : CardMonitor {
        val flow = MutableStateFlow<CardState>(CardState.NotConnected)
        override val state: StateFlow<CardState> = flow
        override fun refresh() {}
        override fun createAccessIntent(volumeId: String): Intent? = null
        override fun onAccessGranted(volumeId: String, treeUri: Uri) {}
    }

    private class FakeAnalysis : AnalysisController {
        var kicks = 0
        override val status = MutableStateFlow(AnalysisStatus())
        override fun kick() { kicks++ }
        override fun regroup() {}
        override suspend fun awaitIdle() {}
    }

    private inner class FakeEnv : ImportEnvironment {
        var free = Long.MAX_VALUE / 2
        val serviceStarts = ArrayList<Boolean>()
        override val storageRoot: File get() = h.phone
        override val destinationFiles: DestinationFiles = DestinationFiles.Default
        override val mediaScanner: MediaScanSink = MediaScanSink.None
        override val removalGraceMs: Long = 0
        override fun freeBytes(): Long = free
        override suspend fun currentCard(): CardState = monitor.flow.value
        override fun openSource(volume: CardVolume): CardSource = h.card
        override fun startService(moveMode: Boolean) {
            serviceStarts += moveMode
        }
    }

    @Before fun setUp() {
        h = EngineHarness(tmp.root)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        monitor = FakeCardMonitor()
        env = FakeEnv()
        analysis = FakeAnalysis()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        controller = ImportControllerImpl(context, scope, h.db, SettingsRepository(context), monitor, analysis, env)
        runBlocking { controller.startupRecovery.join() }
        h.card.put("DCIM/100CANON/IMG_0001.JPG", randomBytes(1, 20_000))
        h.card.put("DCIM/100CANON/IMG_0001.CR3", randomBytes(2, 40_000))
        h.card.put("DCIM/100CANON/MVI_0002.MP4", randomBytes(3, 60_000))
    }

    @After fun tearDown() {
        scope.cancel()
        h.close()
    }

    private fun awaitState(predicate: (ImportState) -> Boolean): ImportState = runBlocking {
        withTimeout(10_000) { controller.state.first(predicate) }
    }

    private fun connect() {
        monitor.flow.value = CardState.Ready(h.volume)
    }

    @Test fun scanWithoutCardFailsWithNoCard() {
        controller.scan()
        assertThat(awaitState { it is ImportState.Failed }).isEqualTo(ImportState.Failed(ImportError.NoCard))
    }

    @Test fun scanWithoutPermission() {
        monitor.flow.value = CardState.NoPermission
        controller.scan()
        assertThat(awaitState { it is ImportState.Failed }).isEqualTo(ImportState.Failed(ImportError.NoPermission))
    }

    @Test fun scanThenCardRemovedGoesBackToIdle() {
        connect()
        controller.scan()
        val ready = awaitState { it is ImportState.Ready } as ImportState.Ready
        assertThat(ready.summary.fileCount).isEqualTo(3)
        assertThat(ready.summary.photoCount).isEqualTo(1)
        assertThat(ready.summary.rawCount).isEqualTo(1)
        assertThat(ready.summary.videoCount).isEqualTo(1)
        assertThat(ready.summary.newBytes).isEqualTo(120_000)
        assertThat(ready.summary.destDisplayPath).isEqualTo("DCIM/R10")

        monitor.flow.value = CardState.NotConnected
        awaitState { it == ImportState.Idle }
    }

    @Test fun startLaunchesServiceAndRunImportFinishes() {
        connect()
        controller.scan()
        awaitState { it is ImportState.Ready }
        controller.start(moveMode = false)
        assertThat(env.serviceStarts).containsExactly(false)
        assertThat(controller.state.value).isInstanceOf(ImportState.Running::class.java)
        assertThat(controller.isImporting).isTrue()

        // 서비스가 하는 일
        val progress = ArrayList<ImportProgress>()
        val outcome = runBlocking { controller.runImport(moveModeOverride = false) { progress += it } }
        assertThat(outcome).isInstanceOf(RunOutcome.Done::class.java)
        val finished = controller.state.value as ImportState.Finished
        assertThat(finished.result.savedCount).isEqualTo(3)
        assertThat(finished.result.moveMode).isFalse()
        assertThat(progress).isNotEmpty()
        assertThat(analysis.kicks).isEqualTo(2) // 대표 사진 1 + 영상 1
        // 복사 모드: 카드 원본 유지
        assertThat(h.card.file("DCIM/100CANON/IMG_0001.JPG").exists()).isTrue()
        assertThat(controller.isImporting).isFalse()

        controller.reset()
        assertThat(controller.state.value).isEqualTo(ImportState.Idle)
        h.assertSafetyInvariant()
    }

    @Test fun startFromCardNotificationUsesSettingsMoveMode() {
        connect()
        // 화면을 거치지 않고 서비스가 바로 시작(설정 기본값 = 이동)
        val outcome = runBlocking { controller.runImport(moveModeOverride = null) }
        outcome as RunOutcome.Done
        assertThat(outcome.outcome.result.moveMode).isTrue()
        assertThat(h.card.file("DCIM/100CANON/IMG_0001.JPG").exists()).isFalse()
        assertThat(controller.state.value).isInstanceOf(ImportState.Finished::class.java)
        h.assertSafetyInvariant()
    }

    @Test fun notEnoughSpaceDoesNotStart() {
        connect()
        env.free = StoragePaths.SPACE_MARGIN_BYTES // 2GB 여유분만 있음 → 새 파일 용량만큼 부족
        controller.scan()
        val ready = awaitState { it is ImportState.Ready } as ImportState.Ready
        assertThat(ready.summary.enoughSpace).isFalse()
        controller.start(moveMode = true)
        assertThat(env.serviceStarts).isEmpty()

        val outcome = runBlocking { controller.runImport(moveModeOverride = null) }
        assertThat(outcome).isInstanceOf(RunOutcome.NotStarted::class.java)
        assertThat((outcome as RunOutcome.NotStarted).error).isInstanceOf(ImportError.InsufficientSpace::class.java)
        assertThat(h.phoneFiles()).isEmpty()
        assertThat(h.card.file("DCIM/100CANON/IMG_0001.JPG").exists()).isTrue()
    }

    @Test fun cancelBeforeServiceRunsReturnsToReady() {
        connect()
        controller.scan()
        awaitState { it is ImportState.Ready }
        controller.start(moveMode = true)
        controller.cancel()
        val outcome = runBlocking { controller.runImport(moveModeOverride = true) }
        assertThat(outcome).isEqualTo(RunOutcome.CancelledBeforeStart)
        assertThat(controller.state.value).isInstanceOf(ImportState.Ready::class.java)
        assertThat(h.phoneFiles()).isEmpty()
        assertThat(h.card.file("DCIM/100CANON/IMG_0001.JPG").exists()).isTrue()
    }

    @Test fun cardRemovedDuringImportFails() {
        connect()
        h.card.removeWhileReading = "IMG_0001.CR3"
        h.card.removeAfterBytes = 1
        val outcome = runBlocking { controller.runImport(moveModeOverride = true) }
        outcome as RunOutcome.Done
        assertThat(controller.state.value).isEqualTo(ImportState.Failed(ImportError.CardRemoved(1, 3)))
        h.assertSafetyInvariant()
    }

    @Test fun startupRecoveryKicksAnalysisForRecoveredPhotos() {
        // 이름 변경 뒤 VERIFIED 기록 전에 종료된 사진: 복구되면 다음 가져오기를 기다리지 않고 바로 분석
        val bytes = randomBytes(9, 5_000)
        val final = File(h.dest.apply { mkdirs() }, "IMG_0009.JPG").apply { writeBytes(bytes) }
        val id = runBlocking {
            val sid = h.ledger.insertSession(ImportSessionEntity(volumeId = h.volume.id, volumeLabel = "x", startedAt = 1, moveMode = true))
            h.ledger.insertItem(
                MediaItemEntity(
                    sessionId = sid, volumeId = h.volume.id, srcRelPath = "DCIM/100CANON/IMG_0009.JPG", srcLocation = "x",
                    srcSize = bytes.size.toLong(), srcLastModified = DEFAULT_MTIME, unitKey = "$sid:DCIM/100CANON/IMG_0009",
                    isPrimary = true, type = MediaType.PHOTO, mimeType = "image/jpeg", dstPath = final.path,
                    fileName = final.name, sha256 = sha256(bytes), importStatus = ImportStatus.COPIED, capturedAt = DEFAULT_MTIME,
                ),
            )
        }
        analysis.kicks = 0
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val restarted = ImportControllerImpl(context, scope, h.db, SettingsRepository(context), monitor, analysis, env)
        runBlocking { restarted.startupRecovery.join() }
        assertThat(runBlocking { h.db.mediaDao().get(id)!!.importStatus }).isEqualTo(ImportStatus.VERIFIED)
        assertThat(analysis.kicks).isEqualTo(1)
    }

    @Test fun serviceStoppingWithoutRunningImportDoesNotLeaveRunning() {
        connect()
        controller.scan()
        awaitState { it is ImportState.Ready }
        controller.start(moveMode = true)
        assertThat(controller.state.value).isInstanceOf(ImportState.Running::class.java)
        // 서비스가 이 요청을 맡지 않고 멈춤 → 화면이 Running에 갇히지 않는다(취소·초기화·스캔 가능)
        controller.onServiceStopped()
        assertThat(controller.state.value).isEqualTo(ImportState.Failed(ImportError.Unexpected("service")))
        assertThat(controller.isImporting).isFalse()
        controller.reset()
        assertThat(controller.state.value).isEqualTo(ImportState.Idle)
    }

    @Test fun serviceStoppedDuringImportKeepsRunningState() {
        connect()
        h.card.chunkSize = 1024
        var during: ImportState? = null
        h.card.onRead = { _, _ ->
            if (during == null) {
                controller.onServiceStopped()
                during = controller.state.value
            }
        }
        runBlocking { controller.runImport(moveModeOverride = false) }
        assertThat(during).isInstanceOf(ImportState.Running::class.java)
        assertThat(controller.state.value).isInstanceOf(ImportState.Finished::class.java)
    }

    @Test fun secondRunWhileImportingIsRejected() {
        connect()
        h.card.chunkSize = 1024
        var second: RunOutcome? = null
        h.card.onRead = { _, _ ->
            if (second == null) second = runBlocking { controller.runImport(moveModeOverride = true) }
        }
        runBlocking { controller.runImport(moveModeOverride = true) }
        assertThat(second).isEqualTo(RunOutcome.AlreadyRunning)
    }
}
