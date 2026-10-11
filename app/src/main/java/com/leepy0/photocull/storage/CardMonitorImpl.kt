package com.leepy0.photocull.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import com.leepy0.photocull.util.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * StorageManager의 이동식 볼륨을 보고 카드 상태를 알려준다(앱 단위 싱글턴).
 * - 볼륨 상태 콜백(registerStorageVolumeCallback)과 [refresh]로 다시 판단한다. 판단은 IO 스레드에서 한다.
 * - 판단 요청이 몰려도 한 번에 하나만, 마지막 요청 기준으로 처리한다(CONFLATED).
 */
class CardMonitorImpl(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val hasAllFilesAccess: () -> Boolean = { runCatching { Permissions.hasAllFilesAccess() }.getOrDefault(false) },
) : CardMonitor {

    private val app: Context = context.applicationContext
    private val accessStore = VolumeAccessStore(app)
    private val _state = MutableStateFlow<CardState>(CardState.NotConnected)
    override val state: StateFlow<CardState> = _state.asStateFlow()

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val evalMutex = Mutex()

    private val volumeCallback = object : StorageManager.StorageVolumeCallback() {
        override fun onStateChanged(volume: StorageVolume) {
            refresh()
        }
    }

    init {
        scope.launch {
            for (request in requests) {
                runCatching { evaluateNow() }.onFailure { Log.w(TAG, "card evaluation failed", it) }
            }
        }
        runCatching {
            app.getSystemService(StorageManager::class.java)
                .registerStorageVolumeCallback(app.mainExecutor, volumeCallback)
        }.onFailure { Log.w(TAG, "volume callback unavailable", it) }
        refresh()
    }

    override fun refresh() {
        requests.trySend(Unit)
    }

    /** 지금 바로 다시 판단하고 결과를 돌려준다(서비스가 앱 프로세스 시작 직후 카드를 확인할 때). */
    suspend fun evaluateNow(): CardState = withContext(Dispatchers.IO) {
        evalMutex.withLock {
            val next = evaluate()
            _state.value = next
            next
        }
    }

    private fun evaluate(): CardState {
        if (!hasAllFilesAccess()) return CardState.NoPermission
        val volumes = runCatching { CardInspector.removableVolumes(app) }.getOrDefault(emptyList())
        if (volumes.isEmpty()) return CardState.NotConnected
        val states = volumes.map { volume ->
            runCatching { CardInspector.inspect(app, volume, accessStore) }
                .getOrElse { CardState.NotCameraCard(CardInspector.label(app, volume)) }
        }
        // 카메라 카드가 여러 개면 첫 번째, 없으면 승인이 필요한 볼륨, 그것도 없으면 카메라 카드 아님
        return states.firstOrNull { it is CardState.Ready }
            ?: states.firstOrNull { it is CardState.NeedsAccess }
            ?: states.first()
    }

    override fun createAccessIntent(volumeId: String): Intent? =
        CardInspector.findVolume(app, volumeId)?.createOpenDocumentTreeIntent()

    override fun onAccessGranted(volumeId: String, treeUri: Uri) {
        // 하위 폴더(예: DCIM/100CANON)를 고르면 상대 경로가 맞지 않아 쓰지 않는다 → 승인 필요 상태 유지
        if (!SafCardSource.isUsableTree(treeUri)) {
            refresh()
            return
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val taken = runCatching { app.contentResolver.takePersistableUriPermission(treeUri, flags) }.isSuccess ||
            runCatching { app.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        if (!taken) {
            refresh()
            return
        }
        val id = SafCardSource.volumeIdOf(treeUri) ?: volumeId
        scope.launch {
            accessStore.save(id, treeUri)
            runCatching { evaluateNow() }
        }
    }

    private companion object {
        const val TAG = "CardMonitor"
    }
}
