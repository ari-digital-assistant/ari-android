package dev.heyari.ari.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.R
import dev.heyari.ari.actions.ActionHandler
import dev.heyari.ari.stt.SpeechRecognizer
import dev.heyari.ari.stt.SttState
import dev.heyari.ari.tts.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.AriEngine
import uniffi.ari_ffi.FfiResponse

/**
 * Possible states a voice interaction can be in. Drives the overlay UI.
 */
sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val partial: String) : VoiceState
    data object Thinking : VoiceState
    data class Responding(val text: String) : VoiceState
    data class Error(val message: String) : VoiceState
}

/**
 * Singleton state machine + pipeline for one voice interaction. Owned by Hilt
 * at the singleton scope so it can be injected by both [WakeWordService] (which
 * triggers the session) and [VoiceOverlayActivity] (which renders the UI).
 *
 * The session does NOT own any UI. The activity observes [state] and finishes
 * itself when the state returns to [VoiceState.Idle].
 *
 * Flow:
 *  1. start() — set state to Listening, drain wake-word audio, open STT
 *  2. STT emits partial — update state
 *  3. STT detects endpoint — feed final text to engine, transition through
 *     Thinking → Responding, speak via TTS, then dismiss
 *  4. dismiss() — reset state to Idle (which causes the activity to finish)
 */
@Singleton
class VoiceSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: AriEngine,
    private val speechRecognizer: SpeechRecognizer,
    private val speechOutput: SpeechOutput,
    private val actionHandler: ActionHandler,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var sessionJob: Job? = null

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    val isActive: Boolean get() = sessionJob?.isActive == true

    /**
     * Begin a voice session. If one is already in progress, do nothing —
     * we don't want re-entrant sessions stomping on each other.
     */
    fun start() {
        if (sessionJob?.isActive == true) {
            Log.w(TAG, "VoiceSession.start() called while already active — ignoring")
            return
        }
        if (!speechRecognizer.isModelLoaded) {
            Log.w(TAG, "No STT model loaded — cannot start voice session")
            _state.value = VoiceState.Error("No speech model installed")
            scope.launch {
                delay(2500)
                dismiss()
            }
            return
        }

        // Set the state synchronously BEFORE launching the coroutine, so any
        // observer (e.g. VoiceOverlayActivity) that attaches a collector
        // immediately after start() returns will see Listening as the first
        // emission rather than the stale Idle value. Otherwise the activity
        // finishes itself in the same millisecond it opened, because the
        // coroutine body that sets Listening hasn't been dispatched yet.
        _state.value = VoiceState.Listening("")

        sessionJob = scope.launch {
            try {
                // Brief pause before opening the STT mic so the tail of the
                // wake-word audio doesn't leak into the user's actual query.
                // Without this, "Hey Jarvis" itself ends up as the transcript.
                delay(WAKE_WORD_DRAIN_DELAY_MS)

                // Open the mic and start the ready-cue tone IN PARALLEL. The
                // mic HAL takes a few hundred ms to actually produce samples
                // after startRecording(), and the cue WAV is ~2 s long — if we
                // played the cue first and then opened the mic, the user would
                // talk into a dead-air gap while the HAL warmed up. Instead we
                // open the mic NOW (it warms up under cover of the cue) and
                // tell sherpa to discard captured samples until the cue has
                // finished, so the tone itself never enters the transcript.
                val cueMs = startReadyCue() + CUE_DISCARD_MARGIN_MS
                speechRecognizer.startListening(discardFirstMs = cueMs)

                // Track activity so we can dismiss after a silence timeout if
                // the user never actually speaks.
                var lastActivityAt = System.currentTimeMillis()
                val silenceWatcher = launch {
                    while (isActive) {
                        delay(1000)
                        val idle = System.currentTimeMillis() - lastActivityAt
                        if (idle > SILENCE_TIMEOUT_MS) {
                            Log.i(TAG, "No speech detected within $SILENCE_TIMEOUT_MS ms — dismissing")
                            dismiss()
                            return@launch
                        }
                    }
                }

                try {
                    speechRecognizer.state.collect { sttState ->
                        when (sttState) {
                            is SttState.Listening -> {
                                if (sttState.partial.isNotBlank()) {
                                    lastActivityAt = System.currentTimeMillis()
                                }
                                _state.update { VoiceState.Listening(sttState.partial) }
                            }
                            is SttState.Done -> {
                                speechRecognizer.reset()
                                silenceWatcher.cancel()
                                handleFinalText(sttState.text)
                                return@collect
                            }
                            is SttState.Error -> {
                                _state.value = VoiceState.Error(sttState.message)
                                silenceWatcher.cancel()
                                delay(2500)
                                dismiss()
                                return@collect
                            }
                            SttState.Idle -> {
                                // ignore
                            }
                        }
                    }
                } finally {
                    silenceWatcher.cancel()
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "Voice session failed", t)
                _state.value = VoiceState.Error(t.message ?: "Unknown error")
                delay(2500)
                dismiss()
            }
        }
    }

    private suspend fun handleFinalText(text: String) {
        if (text.isBlank()) {
            dismiss()
            return
        }

        _state.value = VoiceState.Thinking

        val response = engine.processInput(text)
        val responseText = when (response) {
            is FfiResponse.Text -> response.body
            is FfiResponse.Action -> actionHandler.handle(response.json)
            is FfiResponse.Binary -> "[Binary: ${response.mime}, ${response.data.size} bytes]"
        }

        _state.value = VoiceState.Responding(responseText)
        speechOutput.speak(responseText)

        // Wait long enough for the user to see the response and TTS to roughly finish.
        // Rough-time it based on text length: ~80ms per character, clamped to 3..10 seconds.
        val readMs = (responseText.length * 80L).coerceIn(3000L, 10_000L)
        delay(readMs)
        dismiss()
    }

    /**
     * Start playing the "ready" cue tone (fire-and-forget) and return its
     * duration in milliseconds so the caller knows how long to discard mic
     * samples for. Returns 0 if playback fails to start, in which case there's
     * nothing to discard. Uses ASSISTANCE_SONIFICATION audio attributes so it
     * plays through the notification stream and respects DND assistant rules.
     */
    private fun startReadyCue(): Long {
        val player = MediaPlayer.create(context, R.raw.ready) ?: run {
            Log.w(TAG, "Failed to create MediaPlayer for ready cue — skipping")
            return 0L
        }
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        val durationMs = player.duration.toLong().coerceAtLeast(0L)
        player.setOnCompletionListener { runCatching { it.release() } }
        player.setOnErrorListener { mp, what, extra ->
            Log.w(TAG, "Ready cue playback error what=$what extra=$extra")
            runCatching { mp.release() }
            true
        }
        return runCatching {
            player.start()
            durationMs
        }.getOrElse {
            Log.w(TAG, "Ready cue start() failed", it)
            runCatching { player.release() }
            0L
        }
    }

    fun dismiss() {
        Log.i(TAG, "Dismissing voice session")
        speechRecognizer.stopListening()
        speechRecognizer.reset()
        speechOutput.stop()
        sessionJob?.cancel()
        sessionJob = null
        _state.value = VoiceState.Idle
    }

    companion object {
        private const val TAG = "VoiceSession"
        private const val WAKE_WORD_DRAIN_DELAY_MS = 400L
        private const val SILENCE_TIMEOUT_MS = 8000L
        // Extra ms added to the cue duration when telling sherpa what to drop.
        // Covers MediaPlayer reporting duration slightly short, plus the speaker
        // tail acoustically reaching the mic after the buffer ends.
        private const val CUE_DISCARD_MARGIN_MS = 200L
    }
}
