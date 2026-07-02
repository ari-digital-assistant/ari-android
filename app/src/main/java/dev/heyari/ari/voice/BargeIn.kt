package dev.heyari.ari.voice

/** How the shared mic is being captured. NORMAL = wake-word listening
 *  (AudioSource.MIC); CONVERSATION = comms-mode AEC capture during a talk
 *  session (AudioSource.VOICE_COMMUNICATION). */
enum class CaptureMode { NORMAL, CONVERSATION }

/** Barge-in only runs when the user enabled it AND the device actually offers
 *  a hardware echo canceller. Without AEC, a live mic during TTS would
 *  transcribe Ari, so we stay turn-based. */
fun bargeInEffective(toggleEnabled: Boolean, aecAvailable: Boolean): Boolean =
    toggleEnabled && aecAvailable

/** Cut Ari off only when he is actually speaking and barge-in is effective. */
fun shouldCutTts(speaking: Boolean, bargeInEffective: Boolean): Boolean =
    speaking && bargeInEffective

/** A final tagged with a turn id other than the current one is a straggler
 *  from a superseded turn (e.g. a decode that landed after we cut TTS) and
 *  must be dropped so it can't fire a phantom turn. */
fun isStaleTurn(finalTurnId: Long, currentTurnId: Long): Boolean =
    finalTurnId != currentTurnId
