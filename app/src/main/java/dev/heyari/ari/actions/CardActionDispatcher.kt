package dev.heyari.ari.actions

import android.app.Application
import dev.heyari.ari.notifications.AlertService
import dev.heyari.ari.data.card.CardAction
import dev.heyari.ari.data.card.CardStateRepository
import dev.heyari.ari.model.Attachment
import dev.heyari.ari.di.EngineHolder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.ari_ffi.FfiCardPress
import uniffi.ari_ffi.FfiResponse
import uniffi.ari_ffi.resolveCardPress

/**
 * Single source of truth for what happens when a card button is
 * activated — by tap, by typed text, or by speech (the engine matches
 * the words, [dev.heyari.ari.voice.VoiceSession] presses the button). Centralising here means every input
 * path gets identical behaviour: speak "Cancel" and the on_cancel
 * envelope fires, type "Yes" and the confirmation utterance round-
 * trips, tap "Snooze" and whatever the skill wired up runs. What a press
 * means (reserved ids `stop_alert` and `cancel`, the card's `on_cancel`,
 * the button's `utterance`) is the engine's call, `resolveCardPress`;
 * this class carries it out.
 *
 * Card removal is handled here too so callers don't have to remember
 * to do it. Demo cards (id prefix `card_demo-`) get an extra
 * [CardAlarmScheduler.cancel] because their alarms are managed
 * locally; production cards go through [PresentationCoordinator] which
 * owns its own alarm lifecycle.
 */
@Singleton
class CardActionDispatcher @Inject constructor(
    private val cardRepository: CardStateRepository,
    private val cardAlarmScheduler: CardAlarmScheduler,
    private val actionHandler: ActionHandler,
    private val engineHolder: EngineHolder,
    private val application: Application,
) {
    /** What the caller should render after dispatch. */
    sealed interface Outcome {
        /** Dispatcher handled everything; no rendering needed. */
        object Silent : Outcome

        /**
         * Dispatch produced a response envelope; caller should append
         * a bubble (with [text] + [attachments]) and speak [text] via
         * its own TTS hook.
         */
        data class Spoken(
            val text: String,
            val attachments: List<Attachment>,
        ) : Outcome
    }

    /**
     * Dispatch decides its own thread, the way [EngineHolder.processInput]
     * does, rather than trusting each caller: the voice path calls this from
     * Dispatchers.Main, and [ActionHandler] can block (the media path connects
     * a MediaBrowser and waits up to 3s), which would ANR there. Nothing below
     * touches UI — envelopes drive Intents and StateFlow-backed repos, both
     * safe off Main.
     */
    suspend fun dispatch(cardId: String, action: CardAction): Outcome =
        withContext(Dispatchers.Default) {
            // What the press means is the engine's call (ari_core::envelope::card_press);
            // carrying it out is the phone's.
            val card = cardRepository.state.value.firstOrNull { it.id == cardId }
            val press = resolveCardPress(
                action.id,
                action.utterance,
                action.speak,
                card?.onCancel,
                card?.onComplete?.alert?.id,
            )
            when (press) {
                is FfiCardPress.StopAlert -> {
                    application.startService(AlertService.stopIntent(application, press.alertId))
                    Outcome.Silent
                }
                FfiCardPress.NoAlert -> Outcome.Silent
                is FfiCardPress.Cancel -> runOnCancel(cardId, card?.skillId.orEmpty(), press.onCancel, press.speak)
                is FfiCardPress.Run -> {
                    removeCard(cardId)
                    runUtterance(press.utterance)
                }
                is FfiCardPress.Dismiss -> {
                    removeCard(cardId)
                    spokenOrSilent(press.speak)
                }
            }
        }

    private suspend fun runOnCancel(cardId: String, skillId: String, onCancel: String, speak: String?): Outcome {
        val result = actionHandler.handle(onCancel, skillId)
        val followup = (result as? ActionResult.Spoken)?.followupUtterance
        // The on_cancel envelope often bounces an internal utterance
        // back through the engine (e.g. `aricancelreminder ...`) whose
        // response carries the user-facing acknowledgement ("OK,
        // cancelled that."). Surface that response's speak so the
        // user hears the cancel landed. Fall back to the button's speak
        // when the round-trip didn't produce text, so a skill can still
        // set a static ack.
        val followupSpeak: String? = if (!followup.isNullOrBlank()) {
            when (val response = engineHolder.processInput(followup)) {
                is FfiResponse.Action -> {
                    val r = actionHandler.handle(response.json, response.skillId)
                    r.text.ifBlank { null }
                }
                is FfiResponse.Text -> response.body.ifBlank { null }
                else -> null
            }
        } else null
        removeCard(cardId)
        return spokenOrSilent(followupSpeak ?: speak)
    }

    private fun spokenOrSilent(speak: String?): Outcome =
        if (speak.isNullOrBlank()) Outcome.Silent
        else Outcome.Spoken(speak, emptyList())

    private suspend fun runUtterance(utterance: String): Outcome {
        val response = engineHolder.processInput(utterance)
        return when (response) {
            is FfiResponse.Text -> Outcome.Spoken(response.body, emptyList())
            is FfiResponse.Action -> {
                val r = actionHandler.handle(response.json, response.skillId)
                Outcome.Spoken(r.text, r.attachments)
            }
            is FfiResponse.NotUnderstood -> Outcome.Spoken(response.body, emptyList())
            is FfiResponse.Binary -> Outcome.Spoken(
                "[Binary: ${response.mime}, ${response.data.size} bytes]",
                emptyList(),
            )
        }
    }

    private fun removeCard(cardId: String) {
        cardRepository.removeById(cardId)
        if (cardId.startsWith("card_demo-")) {
            // Demo cards manage their own alarm lifecycle locally;
            // production cards go through PresentationCoordinator.
            cardAlarmScheduler.cancel(cardId)
        }
    }
}
