package dev.heyari.ari.media

import android.service.notification.NotificationListenerService

/**
 * Exists solely so the app can be an *enabled* notification listener, which is
 * the gate Android requires before `MediaSessionManager.getActiveSessions` will
 * return other apps' media sessions (the AVRCP-class transport capability).
 * It intentionally reads nothing — no notification callbacks are overridden.
 */
class AriNotificationListenerService : NotificationListenerService()
