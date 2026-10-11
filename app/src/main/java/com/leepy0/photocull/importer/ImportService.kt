package com.leepy0.photocull.importer

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.leepy0.photocull.PhotoCullApp
import com.leepy0.photocull.analysis.AnalysisController
import com.leepy0.photocull.notify.NotificationChannels
import com.leepy0.photocull.storage.CardNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 가져오기 포그라운드 서비스(dataSync). 시작 즉시 진행 알림으로 포그라운드가 되고,
 * 복사부터 분석 대기가 끝날 때까지 부분 wake lock을 잡는다. 복사가 끝나면 분석이 끝날 때까지 "분석 중 n/m"으로 남아 있다가
 * 결과 알림을 띄우고 멈춘다. 상태는 [ImportControllerImpl]과 같은 프로세스에서 공유한다.
 *
 * 시작 요청이 겹칠 때:
 * - 앞 작업이 복사 중이면 그 작업이 맡는다(같은 카드를 다시 훑는다).
 * - 앞 작업이 복사를 마치고 분석만 기다리는 중이면 기다림을 끝내고(분석은 앱 범위에서 계속) 새로 가져온다.
 * 서비스는 지금 맡은 작업([current])만 멈출 수 있고, 멈출 때는 main 스레드에서 마지막 startId로 stopSelfResult를 부른다
 * (그사이 도착한 시작 요청이 있으면 멈추지 않는다).
 */
class ImportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var notifications: ImportNotifications

    /** 가져오기 작업 한 번. [importDone]: 복사를 마치고 분석 대기·결과 알림만 남음 */
    private class Run(val id: Int) {
        @Volatile var importDone = false
        @Volatile var job: Job? = null
    }

    /** 서비스를 맡은 작업(main 스레드에서만 바꾼다). 다른 작업은 서비스를 멈추지 않는다. */
    @Volatile private var current: Run? = null

    /** onStartCommand로 받은 가장 최근 startId(main 스레드) */
    private var lastStartId = 0

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var wakeRefreshedAt = 0L
    @Volatile private var lastNotifiedAt = 0L
    @Volatile private var cancelling = false

    /** 진행 알림과 멈춤의 순서를 맞춘다: 멈춘 뒤에는 진행(ongoing) 알림을 다시 띄우지 않는다 */
    private val notifyLock = Any()
    private var stopped = false // notifyLock

    /** 마지막으로 띄운 진행 알림(중복 시작 요청에도 진행 표시를 유지) */
    @Volatile private var lastNotification: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = ImportNotifications(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START, ACTION_START_FROM_CARD -> onStartImport(intent, startId)
            ACTION_CANCEL -> {
                cancelling = true
                controller()?.cancel()
                if (isBusy()) showProgress(current, notifications.cancelling()) else stopIfIdle(startId)
            }
            // null(시스템 재시작) 또는 알 수 없는 요청: 할 일이 없으면 바로 멈춘다
            else -> if (!isBusy()) stopIfIdle(startId)
        }
        return START_NOT_STICKY
    }

    private fun isBusy(): Boolean = current?.job?.isActive == true

    private fun onStartImport(intent: Intent, startId: Int) {
        // 카드 연결 알림은 이제 필요 없다(이 요청을 맡든 앞 작업이 맡든)
        CardNotifications.cancel(this)
        val previous = current?.takeIf { it.job?.isActive == true }
        // 앞 작업이 복사 중이면 이 요청은 그 작업이 맡는다. 분석만 기다리는 중이면 이어받아 새로 가져온다.
        val takeOver = previous == null || previous.importDone
        val shown = if (takeOver) notifications.preparing() else lastNotification ?: notifications.preparing()
        // startForegroundService로 시작됐으므로 무엇보다 먼저 포그라운드로
        if (!enterForeground(shown)) {
            controller()?.onServiceStartFailed()
            if (previous == null) stopIfIdle(startId)
            return
        }
        if (!takeOver) return
        synchronized(notifyLock) {
            stopped = false
            lastNotification = shown
        }
        cancelling = false
        val moveMode = if (intent.hasExtra(EXTRA_MOVE_MODE)) intent.getBooleanExtra(EXTRA_MOVE_MODE, true) else null
        val run = Run(startId)
        // 앞 작업은 current가 바뀐 것을 보고 분석 대기를 끝낸 뒤 결과 알림만 띄우고, 서비스는 멈추지 않는다
        current = run
        run.job = scope.launch {
            previous?.job?.join()
            runJob(run, moveMode)
        }
    }

    private fun controller(): ImportControllerImpl? =
        runCatching { PhotoCullApp.container(this).importController as? ImportControllerImpl }.getOrNull()

    private fun enterForeground(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NotificationChannels.ID_IMPORT_PROGRESS,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground failed", e)
        false
    }

    private suspend fun runJob(run: Run, moveMode: Boolean?) {
        try {
            work(run, moveMode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "import job failed", e)
        } finally {
            releaseWakeLock()
        }
        finish(run)
    }

    private suspend fun work(run: Run, moveMode: Boolean?) {
        val container = PhotoCullApp.container(this)
        val controller = container.importController as? ImportControllerImpl ?: return
        // 복사부터 분석 대기가 끝날 때까지 잡는다(화면이 꺼져 CPU가 잠들면 분석이 멈춘다)
        acquireWakeLock()
        val outcome = controller.runImport(moveMode) { p -> onProgress(run, p) }
        run.importDone = true
        when (outcome) {
            RunOutcome.AlreadyRunning -> Unit
            RunOutcome.CancelledBeforeStart -> Unit
            is RunOutcome.NotStarted -> notifications.postError(outcome.error)
            is RunOutcome.Done -> {
                val engine = outcome.outcome
                val error = engine.error
                if (error != null) {
                    notifications.postError(error)
                } else {
                    val result = engine.result
                    if (result.savedCount > 0 && !result.cancelled && !cancelling && current === run) {
                        awaitAnalysis(run, container.analysis)
                    }
                    val counts = if (result.savedCount > 0) {
                        withTimeoutOrNull(COUNTS_TIMEOUT_MS) { runCatching { container.review.counts().first() }.getOrNull() }
                    } else {
                        null
                    }
                    notifications.postResult(result, counts)
                }
            }
        }
    }

    private fun onProgress(run: Run, p: ImportProgress) {
        refreshWakeLock()
        // 알림 갱신은 초당 1회(시스템이 잦은 갱신을 버린다)
        val now = SystemClock.elapsedRealtime()
        if (cancelling || now - lastNotifiedAt < NOTIFY_INTERVAL_MS) return
        lastNotifiedAt = now
        showProgress(run, notifications.progress(p))
    }

    /**
     * 분석이 끝날 때까지 "분석 중 n/m". [취소]를 누르거나, 새 가져오기가 이어받거나, 너무 오래 걸리면 기다리지 않는다.
     * 한도는 elapsedRealtime으로 잰다(기기가 잠든 동안에도 흐른다). 기다리는 동안 wake lock을 이어서 잡는다.
     */
    private suspend fun awaitAnalysis(run: Run, analysis: AnalysisController) = coroutineScope {
        showProgress(run, notifications.analyzing(0, 0))
        val watcher = launch {
            analysis.status.collect { st ->
                if (st.running && st.total > 0) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotifiedAt >= NOTIFY_INTERVAL_MS) {
                        lastNotifiedAt = now
                        showProgress(run, notifications.analyzing(st.done, st.total))
                    }
                }
            }
        }
        val idle = async { analysis.awaitIdle() }
        val deadline = SystemClock.elapsedRealtime() + ANALYSIS_WAIT_MS
        while (idle.isActive && !cancelling && current === run && SystemClock.elapsedRealtime() < deadline) {
            refreshWakeLock()
            delay(ANALYSIS_POLL_MS)
        }
        idle.cancel()
        watcher.cancel()
    }

    /** 지금 서비스를 맡은 작업의 진행 알림만, 멈추기 전까지만 띄운다 */
    private fun showProgress(run: Run?, notification: Notification) {
        synchronized(notifyLock) {
            if (stopped || run == null || current !== run) return
            lastNotification = notification
            notifications.postProgress(notification)
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            wakeRefreshedAt = SystemClock.elapsedRealtime()
        }
    }

    /** 타임아웃이 있는 wake lock을 주기적으로 다시 잡는다 */
    private fun refreshWakeLock() {
        val now = SystemClock.elapsedRealtime()
        if (now - wakeRefreshedAt >= WAKE_REFRESH_MS) {
            wakeRefreshedAt = now
            runCatching { wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /**
     * 작업이 끝남: 아직 이 작업이 서비스를 맡고 있으면 멈춘다. main 스레드에서 해 onStartCommand와 엇갈리지 않게 하고,
     * 마지막 startId로 stopSelfResult를 불러 그사이 도착한 시작 요청이 있으면 멈추지 않는다(그 요청이 새로 시작한다).
     */
    private suspend fun finish(run: Run) = withContext(Dispatchers.Main) {
        if (current !== run) return@withContext
        synchronized(notifyLock) { stopped = true }
        ServiceCompat.stopForeground(this@ImportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (stopSelfResult(lastStartId)) controller()?.onServiceStopped()
    }

    /** 할 일 없이 받은 요청(main 스레드) */
    private fun stopIfIdle(startId: Int) {
        synchronized(notifyLock) { stopped = true }
        if (stopSelfResult(startId)) controller()?.onServiceStopped()
    }

    /** Android 15+: dataSync 실행 시간 한도에 닿음 → 현재 파일을 정리하고 바로 멈춘다 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelling = true
        controller()?.cancel()
        val run = current
        scope.launch {
            withTimeoutOrNull(TIMEOUT_GRACE_MS) { run?.job?.join() }
            withContext(Dispatchers.Main) {
                current = null
                synchronized(notifyLock) { stopped = true }
                ServiceCompat.stopForeground(this@ImportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                controller()?.onServiceStopped()
            }
        }
    }

    override fun onDestroy() {
        // 진행 중이면 코루틴 취소 → 엔진이 .part 정리 후 세션을 마감한다
        scope.cancel()
        releaseWakeLock()
        synchronized(notifyLock) { stopped = true }
        // 멈추는 사이 늦게 올라온 진행(ongoing) 알림이 남지 않게
        runCatching { NotificationManagerCompat.from(this).cancel(NotificationChannels.ID_IMPORT_PROGRESS) }
        controller()?.onServiceStopped()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ImportService"
        const val ACTION_START = "com.leepy0.photocull.action.IMPORT_START"
        const val ACTION_START_FROM_CARD = "com.leepy0.photocull.action.IMPORT_FROM_CARD"
        const val ACTION_CANCEL = "com.leepy0.photocull.action.IMPORT_CANCEL"
        const val EXTRA_MOVE_MODE = "com.leepy0.photocull.extra.MOVE_MODE"

        private const val WAKE_LOCK_TAG = "PhotoCull:import"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKE_REFRESH_MS = 60 * 1000L
        private const val NOTIFY_INTERVAL_MS = 1000L
        private const val ANALYSIS_WAIT_MS = 30 * 60 * 1000L
        private const val ANALYSIS_POLL_MS = 500L
        private const val COUNTS_TIMEOUT_MS = 5_000L
        private const val TIMEOUT_GRACE_MS = 3_000L

        /** 화면에서 [가져오기 시작] */
        fun startIntent(context: Context, moveMode: Boolean): Intent =
            Intent(context, ImportService::class.java).setAction(ACTION_START).putExtra(EXTRA_MOVE_MODE, moveMode)

        /** 카드 연결 알림의 [가져오기]: 카드를 다시 확인하고 설정의 이동/복사로 가져온다 */
        fun startFromCardIntent(context: Context): Intent =
            Intent(context, ImportService::class.java).setAction(ACTION_START_FROM_CARD)

        fun cancelIntent(context: Context): Intent =
            Intent(context, ImportService::class.java).setAction(ACTION_CANCEL)
    }
}
