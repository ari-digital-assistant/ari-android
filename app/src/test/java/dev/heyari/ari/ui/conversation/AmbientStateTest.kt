package dev.heyari.ari.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientStateTest {
    @Test fun idle_when_nothing() =
        assertEquals(AmbientState.Idle, deriveAmbientState(VoicePhase.Idle, textThinking = false))
    @Test fun text_thinking_maps_to_thinking() =
        assertEquals(AmbientState.Thinking, deriveAmbientState(VoicePhase.Idle, textThinking = true))
    @Test fun voice_listening() =
        assertEquals(AmbientState.Listening, deriveAmbientState(VoicePhase.Listening, textThinking = false))
    @Test fun voice_speaking() =
        assertEquals(AmbientState.Speaking, deriveAmbientState(VoicePhase.Speaking, textThinking = false))
    @Test fun speaking_beats_text_thinking() =
        assertEquals(AmbientState.Speaking, deriveAmbientState(VoicePhase.Speaking, textThinking = true))
    @Test fun thinking_beats_listening() =
        assertEquals(AmbientState.Thinking, deriveAmbientState(VoicePhase.Listening, textThinking = true))
}
