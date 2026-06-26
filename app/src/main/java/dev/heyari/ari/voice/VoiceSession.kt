package dev.heyari.ari.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.R
import dev.heyari.ari.actions.ActionHandler
import dev.heyari.ari.stt.SpeechRecognizer
import dev.heyari.ari.stt.SttModelLoader
import dev.heyari.ari.stt.SttState
import dev.heyari.ari.tts.SpeechOutput
import dev.heyari.ari.tts.pleaseWaitPhrase
import dev.heyari.ari.tts.pleaseRepeatPhrase
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
import dev.heyari.ari.di.EngineHolder
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.FfiResponse

/**
 * Possible states a voice interaction can be in. Drives the overlay UI.
 */
sealed interface VoiceState {
    data object Idle : VoiceState
    data class Preparing(val message: String) : VoiceState
    data class Listening(val partial: String) : VoiceState
    data object Thinking : VoiceState
    data class Responding(val text: String) : VoiceState
    data class Error(val message: String) : VoiceState
}

/**
 * Pure decision: does this engine response ask for a spoken reply (and thus
 * warrant re-arming the mic without a second wake word)?
 *
 * Only [FfiResponse.Text] and [FfiResponse.Action] carry a `rearm` flag;
 * everything else (NotUnderstood, Binary) can never re-arm. Kept top-level and
 * free of Android types so it can be unit-tested without Robolectric.
 */
internal fun shouldRearm(response: FfiResponse): Boolean = when (response) {
    is FfiResponse.Text -> response.rearm
    is FfiResponse.Action -> response.rearm
    else -> false
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
 *  1. start() — set state to Listening, drain wake-word audio, open STT.
 *     If the STT model is still warming up (cold start), first show/speak a
 *     "one moment" phrase (Preparing), await the load, then speak a "say that
 *     again" phrase and open STT — the original utterance has aged out of the
 *     capture buffer by then.
 *  2. STT emits partial — update state
 *  3. STT detects endpoint — feed final text to engine, transition through
 *     Thinking → Responding, speak via TTS, then dismiss
 *  4. dismiss() — reset state to Idle (which causes the activity to finish)
 */
@Singleton
class VoiceSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engineHolder: EngineHolder,
    private val speechRecognizer: SpeechRecognizer,
    private val sttModelLoader: SttModelLoader,
    private val speechOutput: SpeechOutput,
    private val actionHandler: ActionHandler,
    private val cardActionVoiceIntercept: dev.heyari.ari.actions.CardActionVoiceIntercept,
    private val cardActionDispatcher: dev.heyari.ari.actions.CardActionDispatcher,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var sessionJob: Job? = null

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    val isActive: Boolean get() = sessionJob?.isActive == true

    // Last moment we saw user/STT activity. Read by the silence watcher; reset
    // by rearmForReply() so a re-armed reply gets a full fresh listening window.
    // A field (not a local in start()) precisely so re-arm can refresh it.
    @Volatile
    private var lastActivityAt: Long = 0L

    // True between a skill asking a question (rearm) and the next final
    // transcript. While true, the legacy CardActionVoiceIntercept shortcut is
    // skipped so the engine's pending-reply path stays authoritative.
    @Volatile
    private var awaitingReply: Boolean = false

    /**
     * Begin a voice session. If one is already in progress, do nothing —
     * we don't want re-entrant sessions stomping on each other.
     */
    fun start() {
        if (sessionJob?.isActive == true) {
            Log.w(TAG, "VoiceSession.start() called while already active — ignoring")
            return
        }
        // A fresh wake cancels any reply the engine was still waiting on from a
        // previous turn — the user has clearly moved on. No-op when nothing is
        // pending.
        engineHolder.peek()?.cancelPendingReply()
        awaitingReply = false
        // Decide readiness synchronously (isModelLoaded is a cheap flag) and
        // set a non-Idle state BEFORE launching the coroutine, so the overlay's
        // collector doesn't see Idle and finish itself (the lock-screen race
        // the wake path must avoid): Listening when warm, Preparing when cold.
        val modelLoaded = speechRecognizer.isModelLoaded
        val waitPhrase = if (modelLoaded) null else pleaseWaitPhrase(context)
        _state.value =
            if (modelLoaded) VoiceState.Listening("")
            else VoiceState.Preparing(waitPhrase!!)

        sessionJob = scope.launch {
            try {
                if (!modelLoaded) {
                    // Cold start: acknowledge, wait for the warm-up, then ask
                    // the user to repeat — their words have already aged out of
                    // the 2 s CaptureBus ring buffer, so there's nothing to
                    // transcribe.
                    speechOutput.speak(waitPhrase!!)
                    val model = sttModelLoader.activeDownloadedModel()
                    if (model == null || !sttModelLoader.load(model)) {
                        _state.value =
                            VoiceState.Error(context.getString(R.string.voice_no_speech_model))
                        delay(2500)
                        dismiss()
                        return@launch
                    }
                    speechOutput.speakAndAwait(pleaseRepeatPhrase(context))
                    _state.value = VoiceState.Listening("")
                    // Zero rewind: the repeat prompt + ready cue must not be
                    // ingested as the user's answer (same guard as rearmForReply).
                    speechRecognizer.startListening(rewindSeconds = 0f)
                } else {
                    // Unified audio pipeline: the mic is already open and the
                    // CaptureBus has been buffering the user's first words since
                    // before the wake-word fired. Arming sherpa is instant.
                    speechRecognizer.startListening()
                }
                startReadyCue()

                // Track activity so we can dismiss after a silence timeout if
                // the user never actually speaks. Uses the class field so a
                // re-armed reply (rearmForReply) can refresh the window.
                lastActivityAt = System.currentTimeMillis()
                // Dismiss after a silence timeout if the user never speaks.
                // Hoisted into a local fun so it can be relaunched for a
                // re-armed reply turn (the previous one self-cancels at Done).
                fun launchSilenceWatcher(): Job = launch {
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
                // The silence watcher is held in a var so we can relaunch it for
                // a re-armed reply turn (the previous one self-cancels at Done).
                var silenceWatcher = launchSilenceWatcher()

                try {
                    speechRecognizer.state.collect { sttState ->
                        when (sttState) {
                            is SttState.Listening -> {
                                if (sttState.partial.isNotBlank()) {
                                    lastActivityAt = System.currentTimeMillis()
                                }
                                _state.update { VoiceState.Listening(sttState.partial) }
                            }
                            SttState.Transcribing -> {
                                // Offline whisper just hit endpoint; the decode
                                // could take seconds. Flip the overlay early so the
                                // user sees we're working, not still listening.
                                silenceWatcher.cancel()
                                _state.update { VoiceState.Thinking }
                            }
                            is SttState.Done -> {
                                speechRecognizer.reset()
                                silenceWatcher.cancel()
                                handleFinalText(
                                    sttState.text,
                                    sttState.parallel,
                                    sttState.audio,
                                )
                                // If the skill asked a follow-up question,
                                // handleFinalText re-armed the mic instead of
                                // dismissing. Keep collecting and relaunch the
                                // silence watcher for the reply turn. Otherwise
                                // the session is done.
                                if (awaitingReply && isActive) {
                                    silenceWatcher = launchSilenceWatcher()
                                } else {
                                    return@collect
                                }
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

        // If the spoken word matches an active card's button (Yes /
        // No / Cancel / Keep — or whatever labels the skill chose),
        // delegate to CardActionDispatcher so the on_cancel envelope,
        // run_utterance, and other generic primitives all fire the
        // same way they would on tap. Falls through to normal engine
        // dispatch when no card is active or no button matches.
        // While re-armed for a skill's follow-up question, the engine holds a
        // pending reply and is authoritative — skip the legacy card-intercept
        // shortcut so the answer flows back through processInput().
        if (!awaitingReply) {
            val intercept = cardActionVoiceIntercept.resolve(text)
            if (intercept != null) {
                val outcome = cardActionDispatcher.dispatch(intercept.cardId, intercept.action)
                when (outcome) {
                    is dev.heyari.ari.actions.CardActionDispatcher.Outcome.Silent -> {
                        dismiss()
                        return
                    }
                    is dev.heyari.ari.actions.CardActionDispatcher.Outcome.Spoken -> {
                        _state.value = VoiceState.Responding(outcome.text)
                        speechOutput.speak(outcome.text)
                        val readMs = (outcome.text.length * 80L).coerceIn(3000L, 10_000L)
                        delay(readMs)
                        dismiss()
                        return
                    }
                }
            }
        }
        // Consume the flag for this turn: from here on this is a normal engine
        // dispatch. If the engine asks another question, rearm flips it back on.
        awaitingReply = false

        val engine = engineHolder.engine()
        var response = engine.processInput(text)
        var usedText = text

        // --- Layer 2 + 3 retries apply to the online streaming path only ---
        // The offline whisper path (non-English locales) sees the full
        // utterance before committing any token, so there's nothing for a
        // parallel decoder or a re-decode to improve. SpeechRecognizer
        // signals the offline path by emitting parallel = null, audio =
        // null in SttState.Done — the null guards below skip the retries
        // automatically. No explicit modelType / locale check needed.

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
            // Voice path doesn't render attachments (the overlay is text-only);
            // coordinator side-effects on the repo still happen so the card
            // shows up in the conversation screen next time it's opened.
            //
            // We're on Dispatchers.Main here. handle() can block — the media
            // path connects a MediaBrowser and waits on a latch for up to 3s —
            // and blocking Main would ANR AND deadlock the MediaBrowser
            // callbacks. handle() touches no UI directly (it dispatches
            // Intents, writes the clipboard, and drives PresentationCoordinator,
            // which mutates StateFlow-backed repos — all thread-safe off Main),
            // so dispatch it to Default exactly like transcribeOffline above.
            is FfiResponse.Action -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                actionHandler.handle(response.json, response.skillId).text
            }
            is FfiResponse.Binary -> "[Binary: ${response.mime}, ${response.data.size} bytes]"
            is FfiResponse.NotUnderstood -> response.body
        }

        // Does the engine want a spoken reply to this response? If so we keep
        // the mic open instead of dismissing. Computed off the final response
        // (after any parallel/offline retry reassignment).
        val rearm = shouldRearm(response)

        _state.value = VoiceState.Responding(responseText)

        if (rearm) {
            // Re-arm AFTER Ari finishes speaking the question. We wait on the
            // TTS completion callback (not a guess-timer): the mic's rewind
            // would otherwise ingest the tail of Ari's own prompt and it would
            // answer its own question ("…to use, Apple Music"). rearmForReply()
            // then opens the mic with zero rewind for the same reason.
            speechOutput.speakAndAwait(responseText)
            rearmForReply()
        } else {
            speechOutput.speak(responseText)
            // Wait long enough for the user to see the response and TTS to roughly finish.
            // Rough-time it based on text length: ~80ms per character, clamped to 3..10 seconds.
            val readMs = (responseText.length * 80L).coerceIn(3000L, 10_000L)
            delay(readMs)
            dismiss()
        }
    }

    /**
     * Re-arm the mic for a skill's follow-up question without a second wake
     * word. State is set to Listening SYNCHRONOUSLY (before startListening)
     * to dodge the lock-screen race the wake path also avoids in [start].
     */
    private fun rearmForReply() {
        awaitingReply = true
        // Give the reply a full fresh silence window — the watcher reads this.
        lastActivityAt = System.currentTimeMillis()
        _state.value = VoiceState.Listening("")
        startListeningAgainCue()
        // Zero rewind: a follow-up answer comes AFTER the prompt, so there's no
        // pre-roll worth keeping — and a non-zero rewind would replay the tail
        // of Ari's just-finished question/cue into the recogniser.
        speechRecognizer.startListening(rewindSeconds = 0f)
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

    /**
     * Softer "I'm still listening" cue played when the mic is re-armed for a
     * skill's follow-up question. Reuses [R.raw.ready] at reduced volume rather
     * than shipping a second audio asset — quiet enough to read as "still
     * here", not "fresh start". A dedicated file can replace it here later with
     * no other changes. Fire-and-forget; failures are swallowed.
     */
    private fun startListeningAgainCue() {
        runCatching {
            MediaPlayer.create(context, R.raw.ready)?.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setVolume(0.3f, 0.3f)
                setOnCompletionListener { runCatching { it.release() } }
                start()
            }
        }
    }

    fun dismiss() {
        Log.i(TAG, "Dismissing voice session")
        // Drop any reply the engine was still waiting on. No-op on the normal
        // happy path (nothing pending); covers tap-dismiss, the silence-timeout
        // path (which calls dismiss()), and lifecycle stop.
        engineHolder.peek()?.cancelPendingReply()
        awaitingReply = false
        speechRecognizer.stopListening()
        speechRecognizer.reset()
        speechOutput.stop()
        sessionJob?.cancel()
        sessionJob = null
        _state.value = VoiceState.Idle
    }

    companion object {
        private const val TAG = "VoiceSession"
        // 30 s — accommodates the offline Whisper path which produces
        // no streaming partials, so `lastActivityAt` can't be refreshed
        // during the utterance + decode window. Online streaming
        // refreshes lastActivityAt on every non-blank partial, so the
        // timeout effectively trips after 30 s of mic-silence; offline
        // gets ~30 s for capture + endpoint-detection + whisper decode
        // combined. Tighter than this and slow x86 emulator decodes
        // (whisper-turbo int8 takes 5-10 s on emulator x86_64) trip
        // the watcher before Done lands.
        private const val SILENCE_TIMEOUT_MS = 30_000L
        // How long to flash the corrected transcript in the overlay before
        // transitioning to the response. Long enough for the user to notice
        // the text changed, short enough not to feel like a stall.
        private const val CORRECTION_FLASH_MS = 600L
    }
}
