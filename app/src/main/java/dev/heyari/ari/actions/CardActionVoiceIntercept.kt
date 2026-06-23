package dev.heyari.ari.actions

import dev.heyari.ari.data.card.CardAction
import dev.heyari.ari.data.card.CardStateRepository
import dev.heyari.ari.data.card.CardStateSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generic intercept that lets the user answer an active card by
 * saying (or typing) the button's label or id — "yes", "no",
 * "cancel", "keep", or whatever the card declared. Skill-independent:
 * any card with [CardAction]s gets the behaviour for free, no
 * skill-specific words baked in here.
 *
 * Match rule: the user input (after trimming and stripping trailing
 * punctuation, both common artefacts of typed input and STT output)
 * must match an action's `id` or `label` case-insensitively. Both
 * single-word ("spotify") and multi-word inputs ("apple music") are
 * eligible — what matters is an exact label/id match, not word count.
 * Inputs that don't match any visible button fall through to the engine.
 *
 * Only the most recent card with actions is consulted; older cards
 * in the conversation are presumed already-handled and shouldn't
 * steal a fresh "yes" the user meant for a new question.
 */
@Singleton
class CardActionVoiceIntercept @Inject constructor(
    private val cardRepository: CardStateSource,
) {
    /**
     * @param cardId   id of the card whose action matched
     * @param skillId  emitting skill id (for asset resolution if the
     *                 dispatch produces an envelope referencing assets)
     * @param action   the matched [CardAction] — the caller dispatches
     *                 [CardAction.utterance] through the engine to fire
     *                 the action's effect.
     */
    data class Match(val cardId: String, val skillId: String, val action: CardAction)

    fun resolve(text: String): Match? {
        val word = text.trim().lowercase().trimEnd('.', '!', '?', ',')
        if (word.isEmpty()) return null

        // Most recent card with at least one action — the one
        // currently prompting the user. `cardRepository.state` is
        // ordered by upsert; lastOrNull picks the freshest match.
        val card = cardRepository.state.value
            .lastOrNull { it.actions.isNotEmpty() }
            ?: return null

        val action = card.actions.firstOrNull {
            it.id.equals(word, ignoreCase = true) ||
                it.label.equals(word, ignoreCase = true)
        } ?: return null

        return Match(card.id, card.skillId, action)
    }
}
