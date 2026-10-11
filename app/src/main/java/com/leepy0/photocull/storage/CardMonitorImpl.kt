package com.leepy0.photocull.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// TODO(storage-import): StorageManager 볼륨 콜백 기반 실제 구현으로 교체
class CardMonitorImpl(private val context: Context) : CardMonitor {
    private val _state = MutableStateFlow<CardState>(CardState.NotConnected)
    override val state: StateFlow<CardState> = _state
    override fun refresh() {}
    override fun createAccessIntent(volumeId: String): Intent? = null
    override fun onAccessGranted(volumeId: String, treeUri: Uri) {}
}
