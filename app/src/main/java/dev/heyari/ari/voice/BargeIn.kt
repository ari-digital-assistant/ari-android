package dev.heyari.ari.voice

import kotlinx.coroutines.Job

/** How the shared mic is being captured. NORMAL = wake-word listening
 *  (AudioSource.VOICE_RECOGNITION); CONVERSATION = comms-mode AEC capture
 *  during a talk session (AudioSource.VOICE_COMMUNICATION). */
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

/** A finishing TTS coroutine may clear the shared `speaking` flag ONLY if it is
 *  still the current ttsJob. On a barge, processInput is a blocking (non-suspend)
 *  FFI call, so the next turn sets speaking=true and installs a NEW ttsJob before
 *  the cancelled job's `finally` gets to run on Main. Without this guard that
 *  stale `finally` would clear the successor's speaking=true, silently breaking
 *  every barge after the first. A null current job (dismiss cleared it) means no
 *  one owns the flag, so nobody clears it here — dismiss() clears it directly.
 *  Identity comparison, so equal-but-distinct jobs never collide. */
fun ownsSpeakingFlag(currentJob: Job?, thisJob: Job?): Boolean =
    currentJob != null && currentJob === thisJob
