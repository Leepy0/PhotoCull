package com.leepy0.photocull.storage

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.leepy0.photocull.AppDestinations
import com.leepy0.photocull.R
import com.leepy0.photocull.importer.ImportNotifications
import com.leepy0.photocull.importer.ImportService
import com.leepy0.photocull.notify.NotificationChannels
import com.leepy0.photocull.util.Format

/** 새로 가져올 사진·영상 수(이미 가져온 파일 제외) */
data class CardCounts(val photos: Int, val videos: Int, val newFiles: Int)

/** 카드 연결 알림: "카메라 카드가 연결됨 · 사진 n · 영상 m" + [가져오기] */
object CardNotifications {

    private const val REQ_OPEN = 1001
    private const val REQ_IMPORT = 1002

    /** @param counts null이면 시간 안에 세지 못함(라벨만 표시) */
    fun showCameraCard(context: Context, label: String, counts: CardCounts?) {
        val text = when {
            counts == null -> context.getString(R.string.imp_card_tap_to_check)
            counts.newFiles == 0 -> context.getString(R.string.imp_card_nothing_new)
            else -> listOfNotNull(
                counts.photos.takeIf { it > 0 }?.let { context.getString(R.string.imp_card_photos, Format.count(it)) },
                counts.videos.takeIf { it > 0 }?.let { context.getString(R.string.imp_card_videos, Format.count(it)) },
            ).joinToString(" · ").ifEmpty { context.getString(R.string.imp_card_new_files, Format.count(counts.newFiles)) }
        }
        val builder = baseBuilder(context, label).setContentText(text)
        // 새로 가져올 것이 없으면 [가져오기]를 두지 않는다(누르면 아무 일도 없는 버튼이 되므로)
        if (counts == null || counts.newFiles > 0) {
            val importIntent = PendingIntent.getForegroundService(
                context,
                REQ_IMPORT,
                ImportService.startFromCardIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, context.getString(R.string.imp_action_import), importIntent)
        }
        post(context, builder)
    }

    /** 경로로 읽을 수 없는 카드: 앱에서 폴더를 한 번 승인해야 한다 */
    fun showNeedsAccess(context: Context, label: String) {
        post(context, baseBuilder(context, label).setContentText(context.getString(R.string.imp_card_needs_access)))
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NotificationChannels.ID_CARD) }
    }

    private fun baseBuilder(context: Context, label: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, NotificationChannels.CARD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.imp_card_title))
            .setSubText(label)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    REQ_OPEN,
                    AppDestinations.intent(context, AppDestinations.IMPORT),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

    @SuppressLint("MissingPermission")
    private fun post(context: Context, builder: NotificationCompat.Builder) {
        if (!ImportNotifications.canNotify(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(NotificationChannels.ID_CARD, builder.build()) }
    }
}
