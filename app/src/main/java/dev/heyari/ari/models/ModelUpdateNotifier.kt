package dev.heyari.ari.models

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
 * Builds and posts the "model updates available" notification.
 *
 * Mirrors [dev.heyari.ari.skills.SkillUpdateNotifier] in spirit but is a
 * separate channel + ID so users can mute one without muting the other.
 * Tapping deep-links into Settings → Auto-update where the user can
 * apply or skip per-update.
 */
@Singleton
class ModelUpdateNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    @JvmName("showOrUpdateUpdates")
    fun showOrUpdate(updates: List<ModelUpdate>) {
        showOrUpdate(updates.map { context.getString(it.target.displayNameRes) })
    }

    /**
     * Lightweight overload — the notifier only ever reads display names off
     * the update list, so passing names directly lets non-Settings callers
     * (e.g. the in-app banner repository) post the same notification
     * without resurrecting full [ModelUpdate] objects from disk.
     */
    @JvmName("showOrUpdateNames")
    fun showOrUpdate(displayNames: List<String>) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (displayNames.isEmpty()) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannel(manager)

        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_AUTO_UPDATE_SETTINGS
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_AUTO_UPDATE, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_AUTO_UPDATE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (displayNames.size == 1) {
            context.getString(R.string.update_banner_model_one)
        } else {
            context.getString(R.string.update_banner_model_many, displayNames.size)
        }
        val body = displayNames.joinToString(", ")

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ari_symbolic)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_model_updates_open_to_review_with_body, body))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notif_model_updates_open_to_review_big, body))
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() {
        context.getSystemService<NotificationManager>()?.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_model_updates_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_model_updates_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "model-updates"
        const val NOTIFICATION_ID = 0x5CE3
        const val ACTION_OPEN_AUTO_UPDATE_SETTINGS = "dev.heyari.ari.action.OPEN_AUTO_UPDATE_SETTINGS"
        const val EXTRA_OPEN_AUTO_UPDATE = "dev.heyari.ari.extra.OPEN_AUTO_UPDATE"
        private const val REQUEST_OPEN_AUTO_UPDATE = 0x5CE4
    }
}
