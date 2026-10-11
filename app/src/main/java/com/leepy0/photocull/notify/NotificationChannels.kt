package com.leepy0.photocull.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.leepy0.photocull.R

/** 알림 채널과 알림 id를 한 곳에서 정의한다. 알림 내용은 각 기능 모듈이 만든다. */
object NotificationChannels {
    const val CARD = "card"
    const val IMPORT = "import"
    const val RESULT = "result"
    const val UPDATE = "update"

    const val ID_CARD = 1001
    const val ID_IMPORT_PROGRESS = 1002
    const val ID_IMPORT_RESULT = 1003
    const val ID_UPDATE = 1004

    fun createAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel(CARD, context.getString(R.string.channel_card), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_card_desc) },
            NotificationChannel(IMPORT, context.getString(R.string.channel_import), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_import_desc); setShowBadge(false) },
            NotificationChannel(RESULT, context.getString(R.string.channel_result), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_result_desc) },
            NotificationChannel(UPDATE, context.getString(R.string.channel_update), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_update_desc) },
        )
        nm.createNotificationChannels(channels)
    }
}
