package dev.heyari.ari.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.data.card.Card
import dev.heyari.ari.notifications.AlertSpecCodec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules `AlarmManager` entries that fire when a card's
 * `countdown_to_ts_ms` is reached. The receiver applies whatever the card's
 * `on_complete` block declared (fire an alert, dismiss the card, etc.).
 *
 * Generic over the timer use case: any skill emitting a card with
 * `countdownToTsMs + onComplete` gets the same scheduling for free. WorkManager
 * is wrong here (Doze rate-limits to ~minutes); `AlarmManager` with
 * `setExactAndAllowWhileIdle` is the right primitive.
 *
 * Permission story is at the manifest level — see `USE_EXACT_ALARM` +
 * `SCHEDULE_EXACT_ALARM` in AndroidManifest.xml. The `SecurityException`
 * catch handles OEM skins that revoke at runtime.
 */
@Singleton
class CardAlarmScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val alarms get() = context.getSystemService<AlarmManager>()

    /** Schedule the card's `on_complete` to fire at `countdownToTsMs`. */
    fun schedule(card: Card) {
        val triggerAtMs = card.countdownToTsMs ?: return
        val onComplete = card.onComplete ?: return
        val am = alarms ?: return

        val intent = Intent(context, CardExpiryReceiver::class.java)
            .setAction(CardExpiryReceiver.ACTION_FIRE)
            .putExtra(CardExpiryReceiver.EXTRA_CARD_ID, card.id)
            .putExtra(CardExpiryReceiver.EXTRA_CARD_TITLE, card.title)
            .putExtra(CardExpiryReceiver.EXTRA_DISMISS_CARD, onComplete.dismissCard)
        // Alert spec rides as a JSON-string extra (Parcelable would mean
        // pulling in kotlin-parcelize for one type; the codec already
        // exists for persistence so reuse it).
        onComplete.alert?.let {
            intent.putExtra(CardExpiryReceiver.EXTRA_ALERT_SPEC_JSON, AlertSpecCodec.encode(it))
        }

        val pi = PendingIntent.getBroadcast(
            context,
            card.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } catch (t: SecurityException) {
            Log.w(TAG, "exact alarm refused — falling back to inexact", t)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    fun cancel(cardId: String) {
        val am = alarms ?: return
        // Match the same intent shape as `schedule` so the PendingIntent
        // resolves to the same one we previously enqueued.
        val intent = Intent(context, CardExpiryReceiver::class.java)
            .setAction(CardExpiryReceiver.ACTION_FIRE)
            .putExtra(CardExpiryReceiver.EXTRA_CARD_ID, cardId)
        val pi = PendingIntent.getBroadcast(
            context,
            cardId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        am.cancel(pi)
    }

    private companion object {
        const val TAG = "CardAlarm"
    }
}
