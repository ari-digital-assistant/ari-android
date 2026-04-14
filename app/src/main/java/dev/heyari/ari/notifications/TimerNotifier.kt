package dev.heyari.ari.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.MainActivity
import dev.heyari.ari.R
import dev.heyari.ari.actions.TimerExpiryReceiver
import dev.heyari.ari.data.timer.Timer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the ongoing countdown notification for a running timer.
 *
 * Uses `setUsesChronometer(true) + setChronometerCountDown(true) +
 * setWhen(endTsMs)` so the platform ticks the display at 1Hz with zero
 * polling from us — countdown survives backgrounding + screen-off.
 *
 * The loud completion alert (sound + TTS loop) is owned by
 * [dev.heyari.ari.notifications.TimerAlertService]; that's a foreground
 * service, not a plain notification. This class retains a
 * [showExpiredWhilePoweredOff] entry for the narrow case where a timer
 * lapsed during a device shutdown and the user deserves to know — silent
 * and one-shot, no loop.
 */
@Singleton
class TimerNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val manager get() = context.getSystemService<NotificationManager>()

    /** Post or update the ongoing countdown for [timer]. Id is stable. */
    fun showOngoing(timer: Timer) {
        val nm = manager ?: return
        val title = timer.name?.let { "${it.replaceFirstChar(Char::titlecase)} timer" } ?: "Timer"

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val cancelIntent = PendingIntent.getBroadcast(
            context,
            timer.id.hashCode(),
            Intent(context, TimerExpiryReceiver::class.java)
                .setAction(TimerExpiryReceiver.ACTION_CANCEL_FROM_NOTIFICATION)
                .putExtra(TimerExpiryReceiver.EXTRA_TIMER_ID, timer.id)
                .putExtra(TimerExpiryReceiver.EXTRA_TIMER_NAME, timer.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, NotificationChannels.TIMER_ONGOING)
            .setSmallIcon(R.drawable.ic_ari_symbolic)
            .setContentTitle(title)
            .setContentText("Running…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(timer.endTsMs)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Cancel", cancelIntent)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setColorized(true)
        }

        nm.notify(ongoingId(timer.id), builder.build())
    }

    /** Remove the ongoing countdown for a cancelled or finished timer. */
    fun dismissOngoing(timerId: String) {
        manager?.cancel(ongoingId(timerId))
    }

    /**
     * Single silent notification used only when [BootReceiver] finds timers
     * whose `endTsMs` passed while the device was powered off. Not the loud
     * alert — the moment is already gone; we just record that it happened
     * so the user isn't left wondering why their pasta overcooked.
     */
    fun showExpiredWhilePoweredOff(timerId: String, name: String?) {
        val nm = manager ?: return
        val title = name?.let { "${it.replaceFirstChar(Char::titlecase)} timer missed" } ?: "Timer missed"
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, NotificationChannels.TIMER_ALERT)
            .setSmallIcon(R.drawable.ic_ari_symbolic)
            .setContentTitle(title)
            .setContentText("Expired while your device was off")
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
        nm.notify(missedId(timerId), builder.build())
    }

    private companion object {
        // Hash the id into stable positive ints; tag-XOR keeps the ongoing
        // and missed buckets distinct so multiple notifications for a single
        // timer don't collide.
        fun ongoingId(timerId: String): Int = (timerId.hashCode() xor 0x71_4d_00_01) and 0x7FFFFFFF
        fun missedId(timerId: String): Int = (timerId.hashCode() xor 0x71_4d_00_03) and 0x7FFFFFFF
    }
}
