package com.leepy0.photocull.importer

import android.app.Service
import android.content.Intent
import android.os.IBinder

// TODO(storage-import): 포그라운드 서비스(dataSync) 실제 구현으로 교체
class ImportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
