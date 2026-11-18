package dev.heyari.ari.bugreport

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts "someone replied to your bug report", one notification per report.
 * Tapping it opens the issue in the browser, the same place My Reports sends
 * the tester.
 */
@Singleton
class ReportReplyNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun notify(report: FiledReportRecord) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        ensureChannel(manager)

        val open = PendingIntent.getActivity(
            context,
            report.issueNumber,
            Intent(Intent.ACTION_VIEW, report.issueUrl.toUri()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ari_symbolic)
            .setContentTitle(context.getString(R.string.notif_report_reply_title))
            .setContentText(report.title.ifBlank { context.getString(R.string.my_reports_untitled) })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // Tagged, so the issue number can be the id without colliding with
        // the app's other notification ids.
        manager.notify(NOTIFICATION_TAG, report.issueNumber, notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_report_replies_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_report_replies_description)
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "report-replies"
        const val NOTIFICATION_TAG = "report-reply"
    }
}
