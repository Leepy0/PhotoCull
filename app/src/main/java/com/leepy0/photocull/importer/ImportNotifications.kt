package com.leepy0.photocull.importer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.leepy0.photocull.AppDestinations
import com.leepy0.photocull.R
import com.leepy0.photocull.data.review.ReviewCounts
import com.leepy0.photocull.notify.NotificationChannels
import com.leepy0.photocull.util.Format
import kotlin.math.roundToInt

/** 가져오기 진행·분석·결과·오류 알림 */
class ImportNotifications(context: Context) {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    private fun progressBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, NotificationChannels.IMPORT)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent(AppDestinations.IMPORT))

    fun preparing(): Notification = progressBuilder()
        .setContentTitle(context.getString(R.string.imp_progress_title))
        .setContentText(context.getString(R.string.imp_progress_preparing))
        .setProgress(0, 0, true)
        .addAction(0, context.getString(R.string.imp_action_cancel), cancelIntent())
        .build()

    fun progress(p: ImportProgress): Notification {
        val percent = (p.fraction * 100).roundToInt().coerceIn(0, 100)
        val counts = arrayOf(Format.count(p.doneFiles), Format.count(p.totalFiles))
        val eta = p.etaSeconds
        val text = if (eta != null) {
            context.getString(R.string.imp_progress_text, counts[0], counts[1], percent, Format.eta(eta))
        } else {
            context.getString(R.string.imp_progress_text_no_eta, counts[0], counts[1], percent)
        }
        return progressBuilder()
            .setContentTitle(context.getString(R.string.imp_progress_title))
            .setContentText(text)
            .setSubText(p.currentFileName)
            .setProgress(100, percent, false)
            .addAction(0, context.getString(R.string.imp_action_cancel), cancelIntent())
            .build()
    }

    fun cancelling(): Notification = progressBuilder()
        .setContentTitle(context.getString(R.string.imp_progress_title))
        .setContentText(context.getString(R.string.imp_progress_cancelling))
        .setProgress(0, 0, true)
        .build()

    /** 가져오기가 끝나고 분석을 기다리는 동안. total이 0이면 막대 대신 안내 문구 */
    fun analyzing(done: Int, total: Int): Notification {
        val b = progressBuilder()
            .setContentTitle(context.getString(R.string.imp_analysis_title))
            .setContentIntent(openIntent(AppDestinations.REVIEW))
        return if (total > 0) {
            b.setContentText(context.getString(R.string.imp_analysis_text, Format.count(done), Format.count(total)))
                .setProgress(total, done.coerceIn(0, total), false)
                .build()
        } else {
            b.setContentText(context.getString(R.string.imp_analysis_preparing)).setProgress(0, 0, true).build()
        }
    }

    fun postProgress(notification: Notification) = post(NotificationChannels.ID_IMPORT_PROGRESS, notification)

    /** 결과: "가져오기 완료 · 324개 이동" + "흐림 12장 · 연사 8그룹" (탭 → 검토, 실패가 있으면 가져오기 결과) */
    fun postResult(result: ImportResult, counts: ReviewCounts?) {
        val saved = Format.count(result.savedCount)
        val title = when {
            result.cancelled && result.moveMode -> context.getString(R.string.imp_result_cancelled_moved, saved)
            result.cancelled -> context.getString(R.string.imp_result_cancelled_copied, saved)
            result.savedCount == 0 -> context.getString(R.string.imp_result_nothing_new)
            result.moveMode -> context.getString(R.string.imp_result_moved, saved)
            else -> context.getString(R.string.imp_result_copied, saved)
        }
        val lines = buildList {
            if (result.savedCount > 0 && counts != null) {
                add(
                    if (counts.blur + counts.groups > 0) {
                        context.getString(R.string.imp_result_review, Format.count(counts.blur), Format.count(counts.groups))
                    } else {
                        context.getString(R.string.imp_result_review_clean)
                    },
                )
            }
            if (result.failedCount > 0) add(context.getString(R.string.imp_result_failed, Format.count(result.failedCount)))
            if (result.sourceDeleteFailedCount > 0) {
                add(context.getString(R.string.imp_result_delete_failed, Format.count(result.sourceDeleteFailedCount)))
            }
            if (result.savedCount == 0 && result.duplicateCount > 0) {
                add(context.getString(R.string.imp_result_skipped, Format.count(result.duplicateCount)))
            }
            if (result.moveMode && !result.cancelled) add(context.getString(R.string.imp_result_eject_hint))
        }
        val hasProblems = result.failedCount > 0 || result.sourceDeleteFailedCount > 0
        val destination = if (!hasProblems && result.savedCount > 0) AppDestinations.REVIEW else AppDestinations.IMPORT
        val builder = NotificationCompat.Builder(context, NotificationChannels.RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openIntent(destination))
        if (lines.isNotEmpty()) {
            builder.setContentText(lines.first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
        }
        post(NotificationChannels.ID_IMPORT_RESULT, builder.build())
    }

    /** 시작하지 못했거나 중간에 멈춘 이유와 다음 행동 */
    fun postError(error: ImportError) {
        val (title, text) = when (error) {
            is ImportError.CardRemoved -> context.getString(R.string.imp_error_card_removed_title) to
                context.getString(R.string.imp_error_card_removed_text, Format.count(error.doneFiles), Format.count(error.totalFiles))
            ImportError.NoCard -> context.getString(R.string.imp_error_no_card_title) to
                context.getString(R.string.imp_error_no_card_text)
            ImportError.NoPermission -> context.getString(R.string.imp_error_no_permission_title) to
                context.getString(R.string.imp_error_no_permission_text)
            is ImportError.InsufficientSpace -> context.getString(R.string.imp_error_space_title) to
                context.getString(R.string.imp_error_space_text, Format.bytes((error.requiredBytes - error.freeBytes).coerceAtLeast(0)))
            is ImportError.Unexpected -> context.getString(R.string.imp_error_unexpected_title) to
                context.getString(R.string.imp_error_unexpected_text)
        }
        val notification = NotificationCompat.Builder(context, NotificationChannels.RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(openIntent(AppDestinations.IMPORT))
            .build()
        post(NotificationChannels.ID_IMPORT_RESULT, notification)
    }

    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        if (!canNotify(context)) return
        runCatching { manager.notify(id, notification) }
    }

    private fun openIntent(destination: String): PendingIntent = PendingIntent.getActivity(
        context,
        if (destination == AppDestinations.REVIEW) REQ_OPEN_REVIEW else REQ_OPEN_IMPORT,
        AppDestinations.intent(context, destination),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        context,
        REQ_CANCEL,
        ImportService.cancelIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val REQ_OPEN_IMPORT = 2001
        private const val REQ_OPEN_REVIEW = 2002
        private const val REQ_CANCEL = 2003

        /** 알림 권한(13+)과 앱 알림 허용 여부 */
        fun canNotify(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
    }
}
