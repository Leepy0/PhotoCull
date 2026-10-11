package com.leepy0.photocull.update

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.leepy0.photocull.PhotoCullApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 하루 한 번(네트워크 연결 시) 백그라운드 업데이트 확인. 새 버전이면 알림(같은 버전은 한 번만).
 * 실패는 조용히 넘기고 다음 주기에 다시 확인한다.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = runCatching { PhotoCullApp.container(applicationContext) }.getOrNull() ?: return Result.success()
        val autoCheck = runCatching { container.settings.current().autoUpdateCheck }.getOrDefault(true)
        if (!autoCheck) return Result.success()
        val controller = container.update as? UpdateControllerImpl ?: return Result.success()

        return when (val result = controller.backgroundCheck()) {
            is CheckResult.Available -> {
                // 앱을 보고 있으면 홈 화면의 업데이트 칩으로 충분하다.
                if (!isAppVisible()) UpdateNotifications.notifyAvailable(applicationContext, result.info)
                Result.success()
            }
            is CheckResult.UpToDate -> Result.success()
            is CheckResult.Failed ->
                if (result.reason == CheckFailure.NETWORK && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            null -> Result.success()
        }
    }

    private suspend fun isAppVisible(): Boolean = withContext(Dispatchers.Main) {
        runCatching { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(false)
    }

    companion object {
        const val UNIQUE_NAME = "photocull-update-check"
        private const val MAX_RETRIES = 2
    }
}

/** 백그라운드 확인 예약. 테스트에서는 가짜로 바꿔 끼운다. */
interface UpdateScheduler {
    fun schedule()
    fun cancel()
}

class WorkManagerUpdateScheduler(private val context: Context) : UpdateScheduler {
    override fun schedule() {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UpdateWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UpdateWorker.UNIQUE_NAME)
    }
}
