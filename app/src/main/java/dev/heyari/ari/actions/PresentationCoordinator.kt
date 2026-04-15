package dev.heyari.ari.actions

import android.content.Context
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.data.card.CardStateRepository
import dev.heyari.ari.model.Attachment
import dev.heyari.ari.notifications.AlertRegistry
import dev.heyari.ari.notifications.AlertService
import dev.heyari.ari.notifications.NotificationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies a parsed [PresentationEnvelope] to the Android side: dismiss
 * everything in `dismiss.*` first, then upsert cards (and schedule their
 * `on_complete` alarms), post notifications, and fire alerts immediately.
 *
 * Skill-shape agnostic — knows nothing about timers, reminders, or any
 * future skill type. Anything a skill emits via the presentation
 * primitives flows through here.
 *
 * Returns the list of attachments to surface on the conversation bubble
 * the user just sent. Convention: the first newly-upserted card becomes
 * an [Attachment.Card] on that bubble. Skills emitting multiple cards in
 * one envelope only get one card "attached" to the bubble; the rest are
 * still rendered if there's a global cards strip in the UI (currently no
 * such strip — they live exclusively under their bubble).
 */
@Singleton
class PresentationCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cardRepository: CardStateRepository,
    private val alarmScheduler: CardAlarmScheduler,
    private val notifier: NotificationCoordinator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        observeAlertEndForCardRemoval()
    }

    /**
     * When a card's alert ends — by user Stop, auto-stop cap, or external
     * dismissal via `dismiss.alerts` — remove the card from the repo if
     * the skill asked for it (`on_complete.dismiss_card: true`, the
     * default). [CardExpiryReceiver] defers the removal for cards with an
     * alert precisely so this observer has the chance to keep the card
     * on-screen during the ring; without this handler those cards would
     * linger indefinitely after the alert stopped.
     */
    private fun observeAlertEndForCardRemoval() {
        scope.launch {
            var previous: Set<String> = emptySet()
            AlertRegistry.active.collect { current ->
                val ended = previous - current
                previous = current
                if (ended.isEmpty()) return@collect
                val cards = cardRepository.state.value
                for (card in cards) {
                    val alertId = card.onComplete?.alert?.id ?: continue
                    val dismiss = card.onComplete.dismissCard
                    if (alertId in ended && dismiss) {
                        cardRepository.removeById(card.id)
                    }
                }
            }
        }
    }

    fun apply(envelope: PresentationEnvelope): List<Attachment> {
        // 1. Dismissals — fire AlarmManager cancels, drop notifications.
        for (id in envelope.dismissCardIds) alarmScheduler.cancel(id)
        for (id in envelope.dismissNotificationIds) notifier.dismiss(id)
        for (id in envelope.dismissAlertIds) {
            context.startService(AlertService.stopIntent(context, id))
        }
        // 2. Upsert cards into the repo. AlarmScheduler.schedule is idempotent
        //    (PendingIntent FLAG_UPDATE_CURRENT) so re-emitting a card with
        //    the same id and a moved deadline reschedules cleanly.
        cardRepository.applyEnvelope(envelope.dismissCardIds, envelope.cards)
        for (card in envelope.cards) alarmScheduler.schedule(card)
        // 3. Background notifications.
        for (notif in envelope.notifications) notifier.show(notif)
        // 4. Alerts fire immediately — they're a "right now" event, not a
        //    scheduled one. Skills that want a future alert attach it to a
        //    card's on_complete instead.
        for (alert in envelope.alerts) {
            ContextCompat.startForegroundService(context, AlertService.startIntent(context, alert))
        }
        // 5. Surface the first new card as an attachment on the bubble.
        return envelope.cards.firstOrNull()
            ?.let { listOf(Attachment.Card(it.id)) }
            ?: emptyList()
    }
}
