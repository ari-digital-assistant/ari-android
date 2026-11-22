package dev.heyari.ari.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.ari_ffi.FfiResponse

/**
 * The flags a typed turn acts on. The voice session's own decisions are the
 * engine's, tested there (ari-voice `session`).
 */
class VoiceSessionTest {

    @Test
    fun shouldClearConversation_trueOnlyWhenFlagSet() {
        assertTrue(shouldClearConversation(FfiResponse.Text("Done, fresh start.", null, false, false, false, false, true)))
        assertTrue(shouldClearConversation(FfiResponse.Action("{}", "skill", false, false, false, false, true)))
        assertFalse(shouldClearConversation(FfiResponse.Text("hi", null, false, false, false, false, false)))
        assertFalse(shouldClearConversation(FfiResponse.NotUnderstood("?")))
    }

    @Test
    fun shouldPersistFacts_trueOnlyWhenFlagSet() {
        assertTrue(shouldPersistFacts(FfiResponse.Text("Noted.", null, false, false, false, true, false)))
        assertTrue(shouldPersistFacts(FfiResponse.Action("{}", "skill", false, false, false, true, false)))
        assertFalse(shouldPersistFacts(FfiResponse.Text("hi", null, false, false, false, false, false)))
        assertFalse(shouldPersistFacts(FfiResponse.Binary("image/png", ByteArray(4))))
    }
}
