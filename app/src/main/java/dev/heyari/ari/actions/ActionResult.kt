package dev.heyari.ari.actions

import dev.heyari.ari.model.Attachment

/**
 * What the action handler hands back to the ViewModel.
 *
 * Before timers we only ever returned a String. Rich skill responses need to
 * carry extras — timer cards, later maps/weather/whatever — alongside the
 * human-readable speak text, so the bubble can render them underneath.
 */
sealed class ActionResult {
    /**
     * A bubble with [text] for display + TTS, plus an optional list of
     * [attachments] rendered below the bubble. [displayText] overrides what
     * the bubble shows when the skill wants the written and spoken wording
     * to differ; null means the bubble shows [text]. [followupUtterance] is
     * populated when the envelope carried a `run_utterance` primitive —
     * the ViewModel should re-dispatch it through the engine after
     * rendering the spoken text. Used for skill-round-trip flows
     * inside card `on_cancel` payloads.
     */
    data class Spoken(
        val text: String,
        val attachments: List<Attachment> = emptyList(),
        val followupUtterance: String? = null,
        val displayText: String? = null,
    ) : ActionResult() {
        /** What the bubble renders: [displayText] when set, else [text]. */
        val bubbleText: String get() = displayText ?: text
    }
}
