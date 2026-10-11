package com.leepy0.photocull.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * 볼륨(UUID)별 폴더 승인(SAF) 트리 URI. 카드를 포맷하면 UUID가 바뀌어 다시 승인해야 한다.
 * 저장된 URI라도 시스템의 영구 권한이 사라졌으면 없는 것으로 본다.
 */
class VolumeAccessStore(private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** 유효한 영구 권한이 있는 트리 URI. 디스크를 읽으므로 메인 스레드에서 부르지 않는다. */
    fun treeUri(volumeId: String): Uri? {
        val stored = prefs.getString(key(volumeId), null) ?: return null
        val uri = stored.toUri()
        val granted = runCatching {
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }.getOrDefault(false)
        return if (granted) uri else null
    }

    fun save(volumeId: String, treeUri: Uri) {
        val previous = prefs.getString(key(volumeId), null)
        if (previous != null && previous != treeUri.toString()) release(previous.toUri())
        prefs.edit { putString(key(volumeId), treeUri.toString()) }
    }

    fun forget(volumeId: String) {
        prefs.getString(key(volumeId), null)?.let { release(it.toUri()) }
        prefs.edit { remove(key(volumeId)) }
    }

    private fun release(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun key(volumeId: String) = "tree_$volumeId"

    private companion object {
        const val PREFS = "card_access"
    }
}
