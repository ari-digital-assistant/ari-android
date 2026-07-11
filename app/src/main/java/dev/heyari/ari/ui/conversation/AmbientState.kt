package dev.heyari.ari.ui.conversation

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
