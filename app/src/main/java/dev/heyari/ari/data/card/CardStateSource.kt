package dev.heyari.ari.data.card

import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only view of the current card list. Extracted from
 * [CardStateRepository] so that [dev.heyari.ari.actions.CardActionVoiceIntercept]
 * (and any other read-only consumer) can be tested without a real
 * Android Context.
 */
interface CardStateSource {
    val state: StateFlow<List<Card>>
}
