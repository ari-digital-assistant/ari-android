package dev.heyari.ari.ui.conversation

import dev.heyari.ari.voice.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientMappingTest {
    @Test
    fun `toVoicePhase maps every voice state`() {
        assertEquals(VoicePhase.Idle, VoiceState.Idle.toVoicePhase())
        assertEquals(VoicePhase.Idle, VoiceState.Preparing("x").toVoicePhase())
        assertEquals(VoicePhase.Listening, VoiceState.Listening("").toVoicePhase())
        assertEquals(VoicePhase.Thinking, VoiceState.Thinking.toVoicePhase())
        assertEquals(VoicePhase.Speaking, VoiceState.Responding("hi").toVoicePhase())
        assertEquals(VoicePhase.Idle, VoiceState.Error("e").toVoicePhase())
    }

    @Test
    fun `toOverlayAmbientState maps every state, Preparing shows Thinking`() {
        assertEquals(AmbientState.Idle, VoiceState.Idle.toOverlayAmbientState())
        assertEquals(AmbientState.Thinking, VoiceState.Preparing("x").toOverlayAmbientState())
        assertEquals(AmbientState.Listening, VoiceState.Listening("").toOverlayAmbientState())
        assertEquals(AmbientState.Thinking, VoiceState.Thinking.toOverlayAmbientState())
        assertEquals(AmbientState.Speaking, VoiceState.Responding("hi").toOverlayAmbientState())
        assertEquals(AmbientState.Idle, VoiceState.Error("e").toOverlayAmbientState())
    }
}
