package dev.heyari.ari.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.ari_ffi.FfiResponse

/**
 * Unit tests for the pure re-arm decision.
 *
 * [VoiceSession] itself drives MediaPlayer / SpeechRecognizer / a Main-dispatch
 * coroutine and isn't unit-testable without Robolectric, so per the task brief
 * the load-bearing logic — "does this response ask for a spoken reply?" — is
 * extracted into the framework-free [shouldRearm] and tested directly.
 */
class VoiceSessionTest {

    @Test
    fun `Text with rearm true re-arms`() {
        assertTrue(shouldRearm(FfiResponse.Text(body = "Which one?", rearm = true, enterConversation = false, exitConversation = false)))
    }

    @Test
    fun `Action with rearm true re-arms`() {
        assertTrue(
            shouldRearm(
                FfiResponse.Action(
                    json = "{\"v\":1}",
                    skillId = "dev.heyari.music",
                    rearm = true,
                    enterConversation = false,
                    exitConversation = false,
                )
            )
        )
    }

    @Test
    fun `Text with rearm false does not re-arm`() {
        assertFalse(shouldRearm(FfiResponse.Text(body = "Playing.", rearm = false, enterConversation = false, exitConversation = false)))
    }

    @Test
    fun `Action with rearm false does not re-arm`() {
        assertFalse(
            shouldRearm(
                FfiResponse.Action(
                    json = "{\"v\":1}",
                    skillId = "dev.heyari.music",
                    rearm = false,
                    enterConversation = false,
                    exitConversation = false,
                )
            )
        )
    }

    @Test
    fun `NotUnderstood never re-arms`() {
        assertFalse(shouldRearm(FfiResponse.NotUnderstood(body = "Sorry, I didn't catch that.")))
    }

    @Test
    fun `Binary never re-arms`() {
        assertFalse(shouldRearm(FfiResponse.Binary(mime = "image/png", data = ByteArray(4))))
    }

    @Test
    fun shouldEnterConversation_trueOnlyWhenFlagSet() {
        assertTrue(shouldEnterConversation(FfiResponse.Text("Okay, I'm listening.", false, true, false)))
        assertTrue(shouldEnterConversation(FfiResponse.Action("{}", "skill", false, true, false)))
        assertFalse(shouldEnterConversation(FfiResponse.Text("hi", false, false, false)))
        assertFalse(shouldEnterConversation(FfiResponse.NotUnderstood("?")))
    }

    @Test
    fun shouldExitConversation_trueOnlyWhenFlagSet() {
        assertTrue(shouldExitConversation(FfiResponse.Text("Okay.", false, false, true)))
        assertTrue(shouldExitConversation(FfiResponse.Action("{}", "skill", false, false, true)))
        assertFalse(shouldExitConversation(FfiResponse.Text("hi", false, false, false)))
        assertFalse(shouldExitConversation(FfiResponse.NotUnderstood("?")))
    }
}
