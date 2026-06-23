package dev.heyari.ari.media

import android.service.notification.NotificationListenerService

/**
 * Intentionally empty NotificationListenerService.
 *
 * We do NOT consume notifications here. Its sole purpose is to give us a
 * component the user can authorise for notification access, which in turn
 * lets [dev.heyari.ari.actions.MusicLauncher] pass this component to
 * `MediaSessionManager.getActiveSessions(componentName)` and enumerate the
 * active media sessions of OTHER apps (e.g. YouTube Music) without holding
 * the system-only MEDIA_CONTENT_CONTROL permission.
 *
 * SPIKE: part of the MediaSession -> playFromSearch autoplay experiment.
 */
class AriMediaNotificationListener : NotificationListenerService()
