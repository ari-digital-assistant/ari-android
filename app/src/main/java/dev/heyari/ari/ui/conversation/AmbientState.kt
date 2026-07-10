package dev.heyari.ari.ui.conversation

enum class VoicePhase { Idle, Listening, Thinking, Speaking }
enum class AmbientState { Idle, Listening, Thinking, Speaking }

/** Precedence: Speaking > Thinking > Listening > Idle. `textThinking` is the
 *  typed-path processing flag; voice phases come from the voice pipeline. */
fun deriveAmbientState(voicePhase: VoicePhase, textThinking: Boolean): AmbientState = when {
    voicePhase == VoicePhase.Speaking -> AmbientState.Speaking
    voicePhase == VoicePhase.Thinking || textThinking -> AmbientState.Thinking
    voicePhase == VoicePhase.Listening -> AmbientState.Listening
    else -> AmbientState.Idle
}
