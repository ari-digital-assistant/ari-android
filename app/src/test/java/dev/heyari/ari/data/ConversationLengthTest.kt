package dev.heyari.ari.data

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.ari_ffi.FfiConversationLength

class ConversationLengthTest {

    @Test
    fun `slugs round-trip`() {
        for (length in ConversationLength.entries) {
            assertEquals(length, ConversationLength.fromSlug(length.slug))
        }
    }

    @Test
    fun `a missing or unknown slug keeps the old behaviour`() {
        assertEquals(ConversationLength.SHORT, ConversationLength.fromSlug(null))
        assertEquals(ConversationLength.SHORT, ConversationLength.fromSlug("forever"))
    }

    @Test
    fun `each length maps to its engine counterpart`() {
        assertEquals(FfiConversationLength.SHORT, ConversationLength.SHORT.ffi)
        assertEquals(FfiConversationLength.MEDIUM, ConversationLength.MEDIUM.ffi)
        assertEquals(FfiConversationLength.LONG, ConversationLength.LONG.ffi)
    }
}
