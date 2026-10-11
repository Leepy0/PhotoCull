package com.leepy0.photocull.storage

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.net.toUri
import java.io.File

/**
 * 이동식 볼륨 하나를 보고 카드 상태(카메라 카드인지, 어떤 방식으로 접근할지)를 판단한다.
 * [CardMonitorImpl]과 [MediaMountedReceiver]가 같은 판단을 쓴다. 디스크를 읽으므로 메인 스레드 금지.
 */
object CardInspector {

    private val MOUNTED_STATES = setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)

    private fun storageManager(context: Context): StorageManager =
        context.getSystemService(StorageManager::class.java)

    /** 연결된(읽기 전용 포함) 이동식 볼륨. 쓰기 금지 카드도 복사는 할 수 있으므로 포함한다. */
    fun removableVolumes(context: Context): List<StorageVolume> =
        storageManager(context).storageVolumes.filter { it.isRemovable && !it.isPrimary && it.state in MOUNTED_STATES }

    fun volumeId(volume: StorageVolume): String = volume.uuid ?: volume.directory?.name ?: "removable"

    fun label(context: Context, volume: StorageVolume): String =
        runCatching { volume.getDescription(context) }.getOrNull()?.takeIf { it.isNotBlank() } ?: volumeId(volume)

    fun findVolume(context: Context, volumeId: String): StorageVolume? =
        runCatching { removableVolumes(context) }.getOrDefault(emptyList()).firstOrNull { volumeId(it) == volumeId }

    fun isMounted(context: Context, volumeId: String): Boolean = findVolume(context, volumeId) != null

    /** 브로드캐스트의 경로(file:///storage/XXXX-XXXX)로 볼륨 찾기 */
    fun volumeForPath(context: Context, path: String): StorageVolume? =
        runCatching { storageManager(context).getStorageVolume(File(path)) }.getOrNull()
            ?.takeIf { it.isRemovable && !it.isPrimary }

    fun inspect(context: Context, volume: StorageVolume, accessStore: VolumeAccessStore): CardState {
        val id = volumeId(volume)
        val label = label(context, volume)
        val dir = volume.directory
        // 경로로 목록을 못 읽거나(null) 비어 보이면(일부 기기는 USB 내용을 숨김) 폴더 승인(SAF)으로 확인한다
        if (dir != null && !dir.listFiles().isNullOrEmpty()) {
            val source = FileCardSource(dir)
            return if (source.hasTargetFile()) {
                CardState.Ready(CardVolume(id, label, dir.path, null, CardAccess.DIRECT))
            } else {
                CardState.NotCameraCard(label)
            }
        }
        val tree = accessStore.treeUri(id) ?: return CardState.NeedsAccess(id, label)
        return try {
            val source = SafCardSource(context, tree) { true }
            if (source.hasTargetFile()) {
                CardState.Ready(CardVolume(id, label, null, tree.toString(), CardAccess.SAF))
            } else {
                CardState.NotCameraCard(label)
            }
        } catch (e: SecurityException) {
            // 권한이 철회됨 → 다시 승인 받기
            accessStore.forget(id)
            CardState.NeedsAccess(id, label)
        }
    }

    /** 가져오기용 카드 접근 객체. 연결 여부는 StorageManager의 볼륨 상태로 판단한다. */
    fun openSource(context: Context, volume: CardVolume): CardSource {
        val app = context.applicationContext
        return when (volume.access) {
            CardAccess.DIRECT -> {
                val root = File(requireNotNull(volume.rootPath) { "rootPath" })
                FileCardSource(root) { isMounted(app, volume.id) && root.isDirectory }
            }
            CardAccess.SAF -> SafCardSource(app, requireNotNull(volume.treeUri) { "treeUri" }.toUri()) {
                isMounted(app, volume.id)
            }
        }
    }
}
