package dev.heyari.ari.ui.conversation

import dev.heyari.ari.model.InputSource
import dev.heyari.ari.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageGlyphTest {
    @Test
    fun `typed user message maps to Typed`() {
        val m = Message(text = "hi", isFromUser = true, source = InputSource.Text)
        assertEquals(ModalityGlyph.Typed, modalityGlyph(m))
    }

    @Test
    fun `voice user message maps to Voice`() {
        val m = Message(text = "hi", isFromUser = true, source = InputSource.Voice)
        assertEquals(ModalityGlyph.Voice, modalityGlyph(m))
    }

    @Test
    fun `ari message has no glyph regardless of source`() {
        val m = Message(text = "hi", isFromUser = false, source = InputSource.Voice)
        assertNull(modalityGlyph(m))
    }
}
