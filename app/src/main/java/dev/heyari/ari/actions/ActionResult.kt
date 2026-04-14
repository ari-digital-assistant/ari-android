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
     * [attachments] rendered below the bubble.
     */
    data class Spoken(
        val text: String,
        val attachments: List<Attachment> = emptyList(),
    ) : ActionResult()
}
