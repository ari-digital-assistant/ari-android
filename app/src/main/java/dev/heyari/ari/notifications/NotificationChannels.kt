package dev.heyari.ari.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService

/**
 * Centralised notification channel definitions for the whole app. Call
 * [ensureAll] once at process start (from `AriApplication.onCreate` or the
 * first component that posts anything). Creating the same channel multiple
 * times is a no-op, so callers never have to coordinate who owns what.
 *
 * The wake word service still has its own channels defined in-file because
 * those pre-date this module and moving them risks destabilising the
 * foreground-service lifecycle. The timer channels live here from day one.
 */
object NotificationChannels {
    const val TIMER_ONGOING = "timer_ongoing"
    const val TIMER_ALERT = "timer_alert"

    fun ensureAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService<NotificationManager>() ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                TIMER_ONGOING,
                "Running timers",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Live countdown for active timers"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                TIMER_ALERT,
                "Timer done",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Fires when a timer finishes"
                enableVibration(true)
                setShowBadge(true)
            },
        )
    }
}
