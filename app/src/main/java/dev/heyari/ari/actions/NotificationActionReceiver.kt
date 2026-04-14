package dev.heyari.ari.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import dev.heyari.ari.notifications.AlertService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uniffi.ari_ffi.AriEngine
import javax.inject.Inject

/**
 * Generic router for action-button taps on skill-emitted notifications and
 * alerts. Reserved action ids are intercepted locally; everything else
 * sends the carried `utterance` through `engine.processInput` so the skill
 * gets a normal envelope round-trip.
 *
 * Reserved ids:
 * - `stop_alert` — handled inline at the alert notification level
 *   (see [AlertService] action wiring); not received here.
 * - `dismiss_notification` — clears the named notification locally,
 *   no engine round-trip.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var engine: AriEngine

    override fun onReceive(context: Context, intent: Intent) {
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID).orEmpty()
        val utterance = intent.getStringExtra(EXTRA_UTTERANCE)
        val notifId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
        val alertId = intent.getStringExtra(EXTRA_ALERT_ID_TO_STOP)

        when (actionId) {
            "dismiss_notification" -> {
                notifId?.let {
                    context.getSystemService<android.app.NotificationManager>()
                        ?.cancel(notificationIdFor(it))
                }
            }
            else -> {
                if (alertId != null) {
                    // The alert is still loud — stop it immediately so the
                    // user isn't shouted at while we wait for the engine.
                    context.startService(AlertService.stopIntent(context, alertId))
                }
                if (!utterance.isNullOrBlank()) {
                    scope.launch {
                        runCatching { engine.processInput(utterance) }
                            .onFailure { Log.w(TAG, "engine call from notification action failed", it) }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION = "dev.heyari.ari.NOTIFICATION_ACTION"
        const val EXTRA_ACTION_ID = "action_id"
        const val EXTRA_UTTERANCE = "utterance"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_ALERT_ID_TO_STOP = "alert_id_to_stop"

        private const val TAG = "NotificationAction"

        // Same coroutine-scope pattern as CardExpiryReceiver — receivers
        // can't outlive their 10-second budget on their own.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun intent(
            context: Context,
            actionId: String,
            utterance: String?,
            notificationIdToDismiss: String? = null,
            alertIdToStop: String? = null,
        ): Intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_ACTION_ID, actionId)
            .putExtra(EXTRA_UTTERANCE, utterance)
            .putExtra(EXTRA_NOTIFICATION_ID, notificationIdToDismiss)
            .putExtra(EXTRA_ALERT_ID_TO_STOP, alertIdToStop)

        // Mirrors NotificationCoordinator's idFor — keep in sync if changed
        // there, or refactor both into a shared util.
        private fun notificationIdFor(id: String): Int =
            (id.hashCode() xor 0x71_4d_00_01) and 0x7FFFFFFF
    }
}
