package dev.heyari.ari.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientStateTest {
    @Test fun idle_when_nothing() =
        assertEquals(AmbientState.Idle, deriveAmbientState(VoicePhase.Idle, textThinking = false, wakeArmed = false))
    @Test fun text_thinking_maps_to_thinking() =
        assertEquals(AmbientState.Thinking, deriveAmbientState(VoicePhase.Idle, textThinking = true, wakeArmed = false))
    @Test fun voice_listening() =
        assertEquals(AmbientState.Listening, deriveAmbientState(VoicePhase.Listening, textThinking = false, wakeArmed = false))
    @Test fun voice_speaking() =
        assertEquals(AmbientState.Speaking, deriveAmbientState(VoicePhase.Speaking, textThinking = false, wakeArmed = false))
    @Test fun speaking_beats_text_thinking() =
        assertEquals(AmbientState.Speaking, deriveAmbientState(VoicePhase.Speaking, textThinking = true, wakeArmed = false))
    @Test fun thinking_beats_listening() =
        assertEquals(AmbientState.Thinking, deriveAmbientState(VoicePhase.Listening, textThinking = true, wakeArmed = false))

    // Wake-word "listening mode" switch: armed between turns is the steady
    // Armed state, not the animated Listening one (ari-android#32).
    @Test fun wake_armed_maps_to_armed() =
        assertEquals(AmbientState.Armed, deriveAmbientState(VoicePhase.Idle, textThinking = false, wakeArmed = true))
    @Test fun a_voice_turn_beats_wake_armed() =
        assertEquals(AmbientState.Listening, deriveAmbientState(VoicePhase.Listening, textThinking = false, wakeArmed = true))
    @Test fun only_a_turn_in_progress_animates() {
        assertEquals(
            listOf(AmbientState.Listening, AmbientState.Thinking, AmbientState.Speaking),
            AmbientState.entries.filter { it.animates },
        )
    }
    @Test fun thinking_beats_wake_armed() =
        assertEquals(AmbientState.Thinking, deriveAmbientState(VoicePhase.Idle, textThinking = true, wakeArmed = true))
    @Test fun speaking_beats_wake_armed() =
        assertEquals(AmbientState.Speaking, deriveAmbientState(VoicePhase.Speaking, textThinking = false, wakeArmed = true))
    @Test fun idle_when_disarmed_and_quiet() =
        assertEquals(AmbientState.Idle, deriveAmbientState(VoicePhase.Idle, textThinking = false, wakeArmed = false))
}
