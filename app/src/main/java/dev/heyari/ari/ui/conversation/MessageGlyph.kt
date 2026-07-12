package dev.heyari.ari.ui.conversation

import dev.heyari.ari.model.InputSource
import dev.heyari.ari.model.Message

/** The modality glyph shown in a user message's trailing gutter. */
enum class ModalityGlyph { Typed, Voice }

/**
 * The trailing modality glyph for a chat row, or null when none should show.
 * Only user messages carry a modality glyph; Ari rows show the leading 'A'
 * avatar instead and yield null here.
 */
fun modalityGlyph(message: Message): ModalityGlyph? =
    if (!message.isFromUser) {
        null
    } else when (message.source) {
        InputSource.Text -> ModalityGlyph.Typed
        InputSource.Voice -> ModalityGlyph.Voice
    }
