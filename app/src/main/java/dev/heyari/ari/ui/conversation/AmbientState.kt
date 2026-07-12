package dev.heyari.ari.ui.conversation

import dev.heyari.ari.voice.VoiceState

enum class VoicePhase { Idle, Listening, Thinking, Speaking }
enum class AmbientState { Idle, Listening, Thinking, Speaking }

/**
 * Precedence: Speaking > Thinking > Listening > Idle.
 *  - `textThinking`: the typed-path processing flag.
 *  - `wakeArmed`: the "Hey Ari" always-listening switch is ON. When armed (and
 *    not otherwise busy) the field shows the Listening treatment — the switch
 *    being on IS Ari listening, so the border reflects it directly.
 *  - `voicePhase`: momentary voice-capture phase from the pipeline.
 */
fun deriveAmbientState(
    voicePhase: VoicePhase,
    textThinking: Boolean,
    wakeArmed: Boolean,
): AmbientState = when {
    voicePhase == VoicePhase.Speaking -> AmbientState.Speaking
    voicePhase == VoicePhase.Thinking || textThinking -> AmbientState.Thinking
    voicePhase == VoicePhase.Listening || wakeArmed -> AmbientState.Listening
    else -> AmbientState.Idle
}

/**
 * Map the voice pipeline's [VoiceState] onto the presentation [VoicePhase].
 * Shared by the chat screen and the voice overlay. Preparing (cold STT warm-up)
 * and Error are transient and fold to Idle so the chat aura doesn't twitch on
 * them; Responding is the phase where Ari is speaking back.
 */
fun VoiceState.toVoicePhase(): VoicePhase = when (this) {
    is VoiceState.Idle -> VoicePhase.Idle
    is VoiceState.Preparing -> VoicePhase.Idle
    is VoiceState.Listening -> VoicePhase.Listening
    is VoiceState.Thinking -> VoicePhase.Thinking
    is VoiceState.Responding -> VoicePhase.Speaking
    is VoiceState.Error -> VoicePhase.Idle
}

/**
 * Ambient state for the voice overlay. The overlay has no typed-path or wake
 * inputs, and Preparing shows the Thinking treatment (the overlay renders a
 * "connecting…" spinner for it, so the border/halo should be alive).
 */
fun VoiceState.toOverlayAmbientState(): AmbientState =
    if (this is VoiceState.Preparing) {
        AmbientState.Thinking
    } else {
        deriveAmbientState(toVoicePhase(), textThinking = false, wakeArmed = false)
    }
