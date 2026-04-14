package dev.heyari.ari.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.heyari.ari.data.card.CardStateRepository
import dev.heyari.ari.notifications.AlertService
import dev.heyari.ari.notifications.AlertSpecCodec
import dev.heyari.ari.notifications.NotificationCoordinator
import javax.inject.Inject

/**
 * Fired when a card's `countdown_to_ts_ms` is reached. Reads the
 * skill-declared `on_complete` (alert spec JSON, dismiss-card flag) from
 * the intent extras the [CardAlarmScheduler] stamped on, hands off to
 * [AlertService], and updates the card repository.
 *
 * The Stop action on alert notifications targets [AlertService] directly,
 * so this receiver doesn't need to know about that path.
 */
@AndroidEntryPoint
class CardExpiryReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: CardStateRepository
    @Inject lateinit var notificationCoordinator: NotificationCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) {
            Log.w(TAG, "unknown action: ${intent.action}")
            return
        }
        val cardId = intent.getStringExtra(EXTRA_CARD_ID) ?: return
        val dismissCard = intent.getBooleanExtra(EXTRA_DISMISS_CARD, true)
        val dismissNotifIds = intent.getStringArrayExtra(EXTRA_DISMISS_NOTIFICATION_IDS).orEmpty()
        val specJson = intent.getStringExtra(EXTRA_ALERT_SPEC_JSON)
        val spec = specJson?.let { AlertSpecCodec.decode(it) }

        // Dismiss paired notifications first so the shade entry vanishes
        // at exactly the moment the alert fires, rather than ticking past
        // zero while the alert plays.
        for (notifId in dismissNotifIds) {
            notificationCoordinator.dismiss(notifId)
        }
        if (spec != null) {
            ContextCompat.startForegroundService(context, AlertService.startIntent(context, spec))
        }
        if (dismissCard) {
            repository.removeById(cardId)
        }
    }

    companion object {
        const val ACTION_FIRE = "dev.heyari.ari.CARD_EXPIRY_FIRE"
        const val EXTRA_CARD_ID = "card_id"
        const val EXTRA_CARD_TITLE = "card_title"
        const val EXTRA_DISMISS_CARD = "dismiss_card"
        const val EXTRA_DISMISS_NOTIFICATION_IDS = "dismiss_notification_ids"
        const val EXTRA_ALERT_SPEC_JSON = "alert_spec_json"
        private const val TAG = "CardExpiryReceiver"
    }
}
