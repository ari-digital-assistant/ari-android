package dev.heyari.ari.actions

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import dev.heyari.ari.data.card.CardStateRepository
import dev.heyari.ari.notifications.AlertActivity
import dev.heyari.ari.notifications.AlertService
import dev.heyari.ari.notifications.AlertSpec
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
            maybeLaunchTakeoverDirectly(context, spec)
        }
        if (dismissCard) {
            repository.removeById(cardId)
        }
    }

    /**
     * Android's FSN heuristic suppresses the full-screen-intent activity
     * launch in favour of a heads-up when the emitting app is already
     * top-of-stack at alert-fire time — so users whose device locked with
     * Ari foregrounded never see [AlertActivity]. Bypass it by starting
     * the activity directly from this receiver while the alarm's
     * `tempAllowListReason: ALARM_MANAGER_WHILE_IDLE` 10-second
     * Background Activity Launch grace window is still in effect.
     *
     * Gated on `keyguardLocked` — when the device is unlocked, heads-up is
     * the right UX (don't rip an active user out of their task). The FSN
     * on the alert notification stays wired as the fallback for devices
     * that lock between alert-fire and user action.
     *
     * Doing this at the receiver (vs. inside [AlertService]) matters:
     * Android's BAL rules revoke the alarm's grace as soon as we hand off
     * to the FGS, so the service-level `startActivity` call gets
     * `BAL_BLOCK`. The receiver still holds the grace.
     */
    private fun maybeLaunchTakeoverDirectly(context: Context, spec: AlertSpec) {
        if (!spec.fullTakeover || spec.urgency != AlertSpec.Urgency.CRITICAL) return
        val keyguard = context.getSystemService<KeyguardManager>() ?: return
        if (!keyguard.isKeyguardLocked) return
        runCatching {
            context.startActivity(AlertActivity.intent(context, spec))
        }.onFailure { Log.w(TAG, "direct takeover launch failed", it) }
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
