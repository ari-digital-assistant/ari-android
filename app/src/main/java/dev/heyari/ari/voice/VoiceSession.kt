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
                // Unified audio pipeline: the mic is already open and the
                // CaptureBus has been buffering the user's first words since
                // before the wake-word fired. Arming sherpa is instant — we
                // do it FIRST, then play the ready cue cosmetically. There is
                // no drain delay, no cue-discard window, no mic handover.
                speechRecognizer.startListening()
                startReadyCue()

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
                                handleFinalText(
                                    sttState.text,
                                    sttState.parallel,
                                    sttState.audio,
                                )
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

    private suspend fun handleFinalText(
        text: String,
        parallel: String?,
        audio: ShortArray?,
    ) {
        if (text.isBlank()) {
            dismiss()
            return
        }

        _state.value = VoiceState.Thinking

        var response = engine.processInput(text)
        var usedText = text

        // --- Layer 2: parallel-stream transcript ---
        if (response is FfiResponse.NotUnderstood &&
            !parallel.isNullOrBlank() && parallel != text
        ) {
            Log.i(TAG, "NotUnderstood for '$text' — retrying with parallel '$parallel'")
            val retry = engine.processInput(parallel)
            if (retry !is FfiResponse.NotUnderstood) {
                Log.i(TAG, "Retry succeeded with parallel transcript")
                response = retry
                usedText = parallel
            }
        }

        // --- Layer 3: offline full-buffer retry ---
        if (response is FfiResponse.NotUnderstood && audio != null) {
            Log.i(TAG, "Parallel also failed — running offline retry (${audio.size} samples)")
            // transcribeOffline blocks while sherpa decodes the full
            // buffer. We're already on Main here so dispatch to Default
            // and suspend until it's done.
            val offlineText = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                speechRecognizer.transcribeOffline(audio)
            }
            if (!offlineText.isNullOrBlank() && offlineText != text && offlineText != parallel) {
                Log.i(TAG, "Offline produced '$offlineText' — retrying engine")
                val retry = engine.processInput(offlineText)
                if (retry !is FfiResponse.NotUnderstood) {
                    Log.i(TAG, "Offline retry succeeded")
                    response = retry
                    usedText = offlineText
                }
            }
        }

        // If we used a different transcript (from parallel or offline),
        // briefly flash the corrected text in the overlay so the user
        // sees that Ari corrected itself before the response appears.
        if (usedText != text) {
            _state.value = VoiceState.Listening(usedText)
            delay(CORRECTION_FLASH_MS)
        }

        val responseText = when (response) {
            is FfiResponse.Text -> response.body
            is FfiResponse.Action -> actionHandler.handle(response.json)
            is FfiResponse.Binary -> "[Binary: ${response.mime}, ${response.data.size} bytes]"
            is FfiResponse.NotUnderstood -> response.body
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
        private const val SILENCE_TIMEOUT_MS = 8000L
        // How long to flash the corrected transcript in the overlay before
        // transitioning to the response. Long enough for the user to notice
        // the text changed, short enough not to feel like a stall.
        private const val CORRECTION_FLASH_MS = 600L
    }
}
