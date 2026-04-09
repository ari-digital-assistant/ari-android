package dev.heyari.ari.skills

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.MainActivity
import dev.heyari.ari.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and posts the "skill updates available" notification.
 *
 * The notification is purely informational — tapping it opens Ari's
 * Settings → Skills screen, where the user reviews and applies updates.
 * We never install updates from the background, by design: Keith's call,
 * and the user gets to decide when to pull the trigger.
 */
@Singleton
class SkillUpdateNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * Post (or update) the skill-updates notification. Pass the count so we
     * can render "N updates available". Call with `count = 0` to cancel
     * a previously-posted notification when the user has caught up.
     */
    fun showOrUpdate(count: Int) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (count <= 0) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannel(manager)

        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_SKILLS_SETTINGS
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_SKILLS, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_SKILLS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (count == 1) "1 skill update available" else "$count skill updates available"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("Open Ari to review and install.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Skill updates",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Notifies you when updates are available for installed skills."
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "skill-updates"
        const val NOTIFICATION_ID = 0x5CE1
        const val ACTION_OPEN_SKILLS_SETTINGS = "dev.heyari.ari.action.OPEN_SKILLS_SETTINGS"
        const val EXTRA_OPEN_SKILLS = "dev.heyari.ari.extra.OPEN_SKILLS"
        private const val REQUEST_OPEN_SKILLS = 0x5CE2
    }
}
