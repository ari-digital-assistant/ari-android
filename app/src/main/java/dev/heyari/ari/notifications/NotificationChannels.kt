package dev.heyari.ari.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import dev.heyari.ari.R

/**
 * Centralised notification channel definitions. Call [ensureAll] once at
 * process start (`AriApplication.onCreate`); creating the same channel
 * multiple times is a no-op so callers don't have to coordinate.
 *
 * The wake word service still defines its own channels in-file because
 * those pre-date this module. Presentation-primitive channels live here.
 */
object NotificationChannels {
    /** Persistent skill-emitted shade entries (low importance, silent). */
    const val ONGOING_DEFAULT = "presentation_ongoing_default"
    /** Higher-importance background shade entries (`importance: "high"`). */
    const val ONGOING_HIGH = "presentation_ongoing_high"
    /** Foreground alert notifications driven by [AlertService]. */
    const val ALERT = "presentation_alert"

    fun ensureAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService<NotificationManager>() ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                ONGOING_DEFAULT,
                context.getString(R.string.notif_channel_background_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_background_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ONGOING_HIGH,
                context.getString(R.string.notif_channel_important_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notif_channel_important_description)
                setSound(null, null)
                setShowBadge(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ALERT,
                context.getString(R.string.notif_channel_alerts_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_alerts_description)
                enableVibration(true)
                setShowBadge(true)
                // No setSound here — AlertService plays the alert audio
                // directly via MediaPlayer + TTS with USAGE_ALARM, which
                // handles DND bypass. A channel sound would double-fire.
                setSound(null, null)
            },
        )
    }
}
