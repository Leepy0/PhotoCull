package com.leepy0.photocull.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.leepy0.photocull.AppDestinations
import com.leepy0.photocull.R
import com.leepy0.photocull.notify.NotificationChannels
import com.leepy0.photocull.util.Permissions

/** 업데이트 알림(채널 UPDATE, id ID_UPDATE). 같은 버전은 한 번만 알린다. */
object UpdateNotifications {
    private const val PREFS = "update_notify"
    private const val KEY_NOTIFIED_CODE = "notified_code"

    /** "PhotoCull v0.2.0 업데이트" → 탭하면 업데이트 시트. 이미 알린 버전이면 false */
    fun notifyAvailable(context: Context, info: UpdateInfo): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_NOTIFIED_CODE, 0) >= info.versionCode) return false
        val firstLine = info.notes.lineSequence()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        val text = context.getString(R.string.update_notif_available_text)
        val builder = NotificationCompat.Builder(context, NotificationChannels.UPDATE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_notif_available_title, info.versionName))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (firstLine != null) "$firstLine\n$text" else text))
            .setContentIntent(openIntent(context, AppDestinations.UPDATE))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
        if (!post(context, builder)) return false
        prefs.edit().putInt(KEY_NOTIFIED_CODE, info.versionCode).apply()
        return true
    }

    /** 설치 직후: "PhotoCull v0.2.0 업데이트 완료" → 탭하면 앱 */
    fun notifyUpdated(context: Context, versionName: String) {
        val builder = NotificationCompat.Builder(context, NotificationChannels.UPDATE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_notif_updated_title, versionName))
            .setContentText(context.getString(R.string.update_notif_updated_text))
            .setContentIntent(openIntent(context, AppDestinations.HOME))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
        post(context, builder)
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NotificationChannels.ID_UPDATE) }
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, builder: NotificationCompat.Builder): Boolean {
        val nm = NotificationManagerCompat.from(context)
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || Permissions.hasNotificationPermission(context)
        if (!permitted || !nm.areNotificationsEnabled()) return false
        return runCatching {
            NotificationChannels.createAll(context)
            nm.notify(NotificationChannels.ID_UPDATE, builder.build())
        }.isSuccess
    }

    private fun openIntent(context: Context, destination: String): PendingIntent = PendingIntent.getActivity(
        context,
        destination.hashCode(),
        AppDestinations.intent(context, destination),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
