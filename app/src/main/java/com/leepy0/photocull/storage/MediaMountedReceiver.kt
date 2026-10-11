package com.leepy0.photocull.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.leepy0.photocull.AppContainer
import com.leepy0.photocull.PhotoCullApp
import com.leepy0.photocull.importer.CardScanner
import com.leepy0.photocull.importer.ImportControllerImpl
import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.util.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 카드 연결/분리 브로드캐스트. 앱이 꺼져 있어도 받는다(암시적 브로드캐스트 예외).
 * 연결: goAsync()로 수 초 안에 카메라 카드인지 확인 → 연결 알림(사진·영상 수, [가져오기]).
 * 분리: 연결 알림 제거, 카드 모니터 갱신.
 */
class MediaMountedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_MEDIA_MOUNTED -> {
                val pending = goAsync()
                scope.launch {
                    try {
                        withTimeoutOrNull(HANDLE_TIMEOUT_MS) { onMounted(app, intent) }
                    } catch (e: Exception) {
                        Log.w(TAG, "card check failed", e)
                    } finally {
                        refreshMonitor(app)
                        pending.finish()
                    }
                }
            }
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_BAD_REMOVAL,
            -> {
                CardNotifications.cancel(app)
                refreshMonitor(app)
            }
        }
    }

    private suspend fun onMounted(app: Context, intent: Intent) {
        val container = PhotoCullApp.container(app)
        if (!container.settings.current().cardNotification) return
        // 모든 파일 접근 권한이 없으면 카드를 확인할 수 없다(앱을 열면 권한 안내)
        if (!runCatching { Permissions.hasAllFilesAccess() }.getOrDefault(false)) return
        val controller = container.importController
        if ((controller as? ImportControllerImpl)?.isImporting == true || controller.state.value is ImportState.Running) return

        val path = intent.data?.path ?: return
        val volume = CardInspector.volumeForPath(app, path) ?: return
        when (val card = CardInspector.inspect(app, volume, VolumeAccessStore(app))) {
            is CardState.NeedsAccess -> CardNotifications.showNeedsAccess(app, card.label)
            is CardState.Ready -> {
                // 세기는 따로 돌리고 정해진 시간만 기다린다(큰 카드에서 브로드캐스트 시간 제한을 넘지 않게)
                val counting = scope.async { countNewFiles(app, container, card.volume) }
                val counts = withTimeoutOrNull(COUNT_TIMEOUT_MS) { counting.await() }
                if (counts == null) counting.cancel()
                CardNotifications.showCameraCard(app, card.volume.label, counts)
            }
            else -> Unit // 카메라 카드가 아님
        }
    }

    private suspend fun countNewFiles(app: Context, container: AppContainer, volume: CardVolume): CardCounts? =
        runCatching {
            val files = CardInspector.openSource(app, volume).listTargetFiles()
            val keys = container.db.mediaDao().importedKeys(volume.id)
            val plan = CardScanner.plan(volume, files, keys, freeBytes = 0, destDisplayPath = "")
            CardCounts(plan.newPhotoUnits, plan.newVideoUnits, plan.summary.newFileCount)
        }.getOrNull()

    private fun refreshMonitor(app: Context) {
        runCatching { PhotoCullApp.container(app).cardMonitor.refresh() }
    }

    private companion object {
        const val TAG = "MediaMountedReceiver"
        const val HANDLE_TIMEOUT_MS = 8_000L
        const val COUNT_TIMEOUT_MS = 4_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
