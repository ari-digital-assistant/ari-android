package dev.heyari.ari.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.heyari.ari.messaging.LiveConversations
import dev.heyari.ari.messaging.MessagingServices
import javax.inject.Inject

/**
 * Notification listener, for two unrelated reasons.
 *
 * Being an *enabled* listener is the gate Android requires before
 * `MediaSessionManager.getActiveSessions` returns other apps' media sessions —
 * the AVRCP-class transport capability. That needs no callbacks at all.
 *
 * It also feeds [LiveConversations], so Ari can reply into a conversation the
 * user has just received a message in. That is the only genuinely hands-free
 * way to answer somebody, and it works on every messenger without a line of
 * per-service code.
 *
 * **What this deliberately does not do.** A listener sees every notification on
 * the device. Everything from a package that isn't a known messaging service is
 * dropped here, before anything reads it, so no general record of the user's
 * notifications exists anywhere in Ari. What survives is who a conversation is
 * with and how to answer it — never the message.
 */
@AndroidEntryPoint
class AriNotificationListenerService : NotificationListenerService() {

    @Inject
    lateinit var live: LiveConversations

    @Inject
    lateinit var services: MessagingServices

    /**
     * Take the conversations already sitting in the shade.
     *
     * Without this the listener only ever sees what arrives *after* it
     * connects, so the first message of a session is never repliable — grant
     * notification access with an unanswered message on screen and Ari still
     * can't answer it, which reads as the feature being broken. Connection
     * happens on grant, on boot, and on every rebind after the system kills
     * the service, so this is the common case rather than an edge one.
     */
    override fun onListenerConnected() {
        // Anything held from a previous binding points at a dead PendingIntent.
        live.clear()
        val active = try {
            activeNotifications
        } catch (e: SecurityException) {
            // Disconnected again between the callback and this call.
            Log.w(TAG, "no longer connected; nothing to seed", e)
            return
        }
        active?.forEach { keep(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        keep(sbn)
    }

    /** Offer [sbn] to the store, which decides whether it's a conversation. */
    private fun keep(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(android.app.Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        live.offer(
            key = sbn.key,
            packageName = sbn.packageName,
            title = title,
            postedAtMs = sbn.postTime,
            notification = sbn.notification,
            catalogue = services.all(),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        live.forget(sbn.key)
    }

    override fun onListenerDisconnected() {
        // Every PendingIntent we held belongs to a session that has ended.
        live.clear()
    }

    private companion object {
        const val TAG = "AriNotificationListener"
    }
}
