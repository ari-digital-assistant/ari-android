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
import dev.heyari.ari.actions.NotificationActionReceiver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts and dismisses skill-emitted notification primitives.
 *
 * Generic — knows nothing about timers; everything driven by the
 * [NotificationPrimitive] passed in. `countdownToTsMs` (when present)
 * lights up the OS Chronometer widget so the shade entry counts down at
 * 1Hz with zero polling on our side.
 *
 * The `showExpiredWhilePoweredOff` entry is the narrow case where
 * [BootReceiver] finds a card whose deadline lapsed while the device was
 * off. Silent and one-shot; the loud alert moment is gone.
 */
@Singleton
class NotificationCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val manager get() = context.getSystemService<NotificationManager>()

    fun show(notif: NotificationPrimitive) {
        val nm = manager ?: return
        val channel = when (notif.importance) {
            NotificationPrimitive.Importance.HIGH -> NotificationChannels.ONGOING_HIGH
            else -> NotificationChannels.ONGOING_DEFAULT
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_ari_symbolic)
            .setContentTitle(notif.title)
            .setContentText(notif.body ?: "")
            .setOngoing(notif.sticky)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)

        if (notif.countdownToTsMs != null) {
            builder
                .setShowWhen(true)
                .setWhen(notif.countdownToTsMs)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setColorized(true)
        }

        for (action in notif.actions) {
            val pi = PendingIntent.getBroadcast(
                context,
                (notif.id + action.id).hashCode(),
                NotificationActionReceiver.intent(
                    context = context,
                    actionId = action.id,
                    utterance = action.utterance,
                    notificationIdToDismiss = notif.id,
                ),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, action.label, pi)
        }

        nm.notify(idFor(notif.id), builder.build())
    }

    fun dismiss(notifId: String) {
        manager?.cancel(idFor(notifId))
    }

    /**
     * Posted when [BootReceiver] finds a card whose deadline lapsed while
     * the device was off. Silent and one-shot — the loud alert moment is
     * already past; the user just deserves to know.
     */
    fun showExpiredWhilePoweredOff(cardId: String, title: String) {
        val nm = manager ?: return
        val displayTitle = if (title.endsWith(" missed", ignoreCase = true)) title else "$title missed"
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, NotificationChannels.ALERT)
            .setSmallIcon(R.drawable.ic_ari_symbolic)
            .setContentTitle(displayTitle)
            .setContentText("Expired while your device was off")
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
        nm.notify(missedIdFor(cardId), builder.build())
    }

    private companion object {
        fun idFor(id: String): Int = (id.hashCode() xor 0x71_4d_00_01) and 0x7FFFFFFF
        fun missedIdFor(id: String): Int = (id.hashCode() xor 0x71_4d_00_03) and 0x7FFFFFFF
    }
}
