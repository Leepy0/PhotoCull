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
import androidx.core.app.ServiceCompat
import com.leepy0.photocull.PhotoCullApp
import com.leepy0.photocull.analysis.AnalysisController
import com.leepy0.photocull.notify.NotificationChannels
import com.leepy0.photocull.storage.CardNotifications
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 가져오기 포그라운드 서비스(dataSync). 시작 즉시 진행 알림으로 포그라운드가 되고,
 * 복사하는 동안만 부분 wake lock을 잡는다. 복사가 끝나면 분석이 끝날 때까지 "분석 중 n/m"으로 남아 있다가
 * 결과 알림을 띄우고 멈춘다. 상태는 [ImportControllerImpl]과 같은 프로세스에서 공유한다.
 */
class ImportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private lateinit var notifications: ImportNotifications

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var wakeRefreshedAt = 0L
    @Volatile private var lastNotifiedAt = 0L
    @Volatile private var cancelling = false

    /** 마지막으로 띄운 진행 알림(중복 시작 요청에도 진행 표시를 유지) */
    @Volatile private var lastNotification: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = ImportNotifications(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_START_FROM_CARD -> {
                // startForegroundService로 시작됐으므로 무엇보다 먼저 포그라운드로
                if (!enterForeground()) {
                    controller()?.onServiceStartFailed()
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (job?.isActive == true) return START_NOT_STICKY
                cancelling = false
                val moveMode = if (intent.hasExtra(EXTRA_MOVE_MODE)) intent.getBooleanExtra(EXTRA_MOVE_MODE, true) else null
                // 카드 연결 알림은 이제 필요 없다(가져오는 중에는 진행 알림이 대신한다)
                CardNotifications.cancel(this)
                job = scope.launch { runJob(moveMode) }
            }
            ACTION_CANCEL -> {
                cancelling = true
                controller()?.cancel()
                if (job?.isActive == true) {
                    showProgress(notifications.cancelling())
                } else {
                    stopSelf()
                }
            }
            // null(시스템 재시작) 또는 알 수 없는 요청: 할 일이 없으면 바로 멈춘다
            else -> if (job?.isActive != true) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun controller(): ImportControllerImpl? =
        runCatching { PhotoCullApp.container(this).importController as? ImportControllerImpl }.getOrNull()

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NotificationChannels.ID_IMPORT_PROGRESS,
            lastNotification?.takeIf { job?.isActive == true } ?: notifications.preparing(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground failed", e)
        false
    }

    private suspend fun runJob(moveMode: Boolean?) {
        val container = PhotoCullApp.container(this)
        val controller = container.importController as? ImportControllerImpl
        if (controller == null) {
            finish()
            return
        }
        acquireWakeLock()
        val outcome = try {
            controller.runImport(moveMode) { p -> onProgress(p) }
        } finally {
            releaseWakeLock()
        }
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
                    if (result.savedCount > 0 && !result.cancelled && !cancelling) awaitAnalysis(container.analysis)
                    val counts = if (result.savedCount > 0) {
                        withTimeoutOrNull(COUNTS_TIMEOUT_MS) { runCatching { container.review.counts().first() }.getOrNull() }
                    } else {
                        null
                    }
                    notifications.postResult(result, counts)
                }
            }
        }
        finish()
    }

    private fun onProgress(p: ImportProgress) {
        val now = SystemClock.elapsedRealtime()
        if (now - wakeRefreshedAt >= WAKE_REFRESH_MS) {
            wakeRefreshedAt = now
            runCatching { wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
        // 알림 갱신은 초당 1회(시스템이 잦은 갱신을 버린다)
        if (cancelling || now - lastNotifiedAt < NOTIFY_INTERVAL_MS) return
        lastNotifiedAt = now
        showProgress(notifications.progress(p))
    }

    /** 분석이 끝날 때까지 "분석 중 n/m". [취소]를 누르거나 너무 오래 걸리면 기다리지 않는다. */
    private suspend fun awaitAnalysis(analysis: AnalysisController) = coroutineScope {
        showProgress(notifications.analyzing(0, 0))
        val watcher = launch {
            analysis.status.collect { st ->
                if (st.running && st.total > 0) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotifiedAt >= NOTIFY_INTERVAL_MS) {
                        lastNotifiedAt = now
                        showProgress(notifications.analyzing(st.done, st.total))
                    }
                }
            }
        }
        val idle = async { analysis.awaitIdle() }
        withTimeoutOrNull(ANALYSIS_WAIT_MS) {
            while (idle.isActive && !cancelling) delay(ANALYSIS_POLL_MS)
        }
        idle.cancel()
        watcher.cancel()
    }

    private fun showProgress(notification: Notification) {
        lastNotification = notification
        notifications.postProgress(notification)
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

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15+: dataSync 실행 시간 한도에 닿음 → 현재 파일을 정리하고 바로 멈춘다 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelling = true
        controller()?.cancel()
        scope.launch {
            withTimeoutOrNull(TIMEOUT_GRACE_MS) { job?.join() }
            finish()
        }
    }

    override fun onDestroy() {
        // 진행 중이면 코루틴 취소 → 엔진이 .part 정리 후 세션을 마감한다
        scope.cancel()
        releaseWakeLock()
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
