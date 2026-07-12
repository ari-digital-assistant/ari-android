package dev.heyari.ari.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTest {
    @Test
    fun `source defaults to Text`() {
        val m = Message(text = "hi", isFromUser = true)
        assertEquals(InputSource.Text, m.source)
    }
}
