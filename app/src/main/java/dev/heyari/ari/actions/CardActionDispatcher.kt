package dev.heyari.ari.actions

import android.app.Application
import dev.heyari.ari.notifications.AlertService
import dev.heyari.ari.data.card.CardAction
import dev.heyari.ari.data.card.CardStateRepository
import dev.heyari.ari.model.Attachment
import dev.heyari.ari.di.EngineHolder
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.FfiResponse

/**
 * Single source of truth for what happens when a card button is
 * activated — by tap, by typed text, or by speech (via
 * [CardActionVoiceIntercept]). Centralising here means every input
 * path gets identical behaviour: speak "Cancel" and the on_cancel
 * envelope fires, type "Yes" and the confirmation utterance round-
 * trips, tap "Snooze" and whatever the skill wired up runs. No skill-
 * specific knowledge lives in this class — it dispatches by reserved
 * action id (`stop_alert`, `cancel`) and by the action's `utterance`
 * field, both already documented in the v:1 envelope schema.
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

    suspend fun dispatch(cardId: String, action: CardAction): Outcome {
        return when (action.id) {
            "stop_alert" -> dispatchStopAlert(cardId)
            "cancel" -> dispatchCancel(cardId, action)
            else -> dispatchGeneric(cardId, action)
        }
    }

    private fun dispatchStopAlert(cardId: String): Outcome {
        val card = cardRepository.state.value.firstOrNull { it.id == cardId }
        val alertId = card?.onComplete?.alert?.id ?: return Outcome.Silent
        application.startService(AlertService.stopIntent(application, alertId))
        return Outcome.Silent
    }

    private suspend fun dispatchCancel(cardId: String, action: CardAction): Outcome {
        val card = cardRepository.state.value.firstOrNull { it.id == cardId }

        // Card-level on_cancel envelope wins over a button utterance —
        // it's the documented Layer B mechanism and gives the skill a
        // chance to bounce a clean run_utterance back through the
        // engine without inventing a skill-specific primitive.
        if (card?.onCancel != null) {
            val result = actionHandler.handle(card.onCancel, card.skillId)
            val followup = (result as? ActionResult.Spoken)?.followupUtterance
            // The on_cancel envelope often bounces an internal utterance
            // back through the engine (e.g. `aricancelreminder ...`) whose
            // response carries the user-facing acknowledgement ("OK,
            // cancelled that."). Surface that response's speak so the
            // user hears the cancel landed. Fall back to action.speak
            // when the round-trip didn't produce text, so a skill can
            // still set a static ack.
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
            return spokenOrSilent(followupSpeak ?: action.speak)
        }

        // Back-compat: a "cancel" action that carries an utterance but
        // no card-level on_cancel still round-trips through the engine.
        if (!action.utterance.isNullOrBlank()) {
            removeCard(cardId)
            return runUtterance(action.utterance)
        }

        // Plain dismiss — no on_cancel, no utterance. Optional speak
        // text gives the user feedback that the cancel registered.
        removeCard(cardId)
        return spokenOrSilent(action.speak)
    }

    private suspend fun dispatchGeneric(cardId: String, action: CardAction): Outcome {
        removeCard(cardId)
        val utterance = action.utterance
        if (utterance.isNullOrBlank()) {
            // A no-utterance button (e.g. "Keep" / "No") doesn't need
            // an engine round-trip, but the skill may have set `speak`
            // to acknowledge the dismissal.
            return spokenOrSilent(action.speak)
        }
        return runUtterance(utterance)
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
