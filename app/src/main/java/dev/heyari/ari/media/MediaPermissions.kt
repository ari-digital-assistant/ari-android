package dev.heyari.ari.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/** The component the OS lists under Settings → Notification access. */
fun ariListenerComponent(context: Context): ComponentName =
    ComponentName(context, AriNotificationListenerService::class.java)

/** True when the user has granted Ari notification-listener access. */
fun hasNotificationAccess(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners",
    ) ?: return false
    val target = ariListenerComponent(context).flattenToString()
    // The setting is a colon-separated list of flattened ComponentNames.
    return enabled.split(':').any { it.equals(target, ignoreCase = true) }
}

/** Deep-link to the "Notification access" special-access settings screen. */
fun openNotificationListenerSettings(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        // Fallback to app-details if the listener screen is unavailable.
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData("package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(fallback) }
    }
}
