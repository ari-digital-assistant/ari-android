package dev.heyari.ari.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService

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
                "Background updates",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent skill-driven status (timers, downloads, …)"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ONGOING_HIGH,
                "Important updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Higher-priority skill-driven status the user should see"
                setSound(null, null)
                setShowBadge(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ALERT,
                "Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Skill-fired alerts (timer done, alarm clock, etc.)"
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
