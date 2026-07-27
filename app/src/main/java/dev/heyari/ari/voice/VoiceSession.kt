package dev.heyari.ari.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.R
import dev.heyari.ari.actions.ActionHandler
import dev.heyari.ari.data.conversation.ConversationLogRepository
import dev.heyari.ari.model.Attachment
import dev.heyari.ari.model.InputSource
import dev.heyari.ari.model.Message
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
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
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
 * Post-hoc wake verification. The 64 KB detector fires on phonetic shape and
 * saturates its confidence even when it's wrong, so the threshold has no
 * headroom left; sherpa — which is already transcribing the same audio via the
 * CaptureBus rewind — is the second opinion. A wake turn whose transcript
 * contains speech but no "ari"-ish name token was not addressed to Ari.
 *
 * Fails open on every ambiguity: a wrongly-rejected genuine command reads as
 * "Ari ignored me", which is a worse experience than a spurious chime.
 */
internal fun shouldAcceptWake(
    verifyWake: Boolean,
    raw: String?,
    nameMatched: Boolean?,
): Boolean = when {
    !verifyWake -> true
    nameMatched == null -> true
    raw.isNullOrBlank() -> true
    else -> nameMatched
}

internal fun shouldEnterConversation(response: FfiResponse): Boolean = when (response) {
    is FfiResponse.Text -> response.enterConversation
    is FfiResponse.Action -> response.enterConversation
    else -> false
}

internal fun shouldExitConversation(response: FfiResponse): Boolean = when (response) {
    is FfiResponse.Text -> response.exitConversation
    is FfiResponse.Action -> response.exitConversation
    else -> false
}

/**
 * Did this turn mutate the engine's remembered-fact list (a remember/forget
 * command)? Only [FfiResponse.Text] and [FfiResponse.Action] carry the flag;
 * everything else can never change facts. Top-level and Android-free so it can
 * be unit-tested without Robolectric, matching [shouldEnterConversation].
 */
internal fun shouldPersistFacts(response: FfiResponse): Boolean = when (response) {
    is FfiResponse.Text -> response.factsChanged
    is FfiResponse.Action -> response.factsChanged
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
    private val settingsRepository: dev.heyari.ari.data.SettingsRepository,
    private val logRepository: ConversationLogRepository,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var sessionJob: Job? = null

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    // Final transcript from an in-place dictation session (STT-only, no engine).
    // extraBufferCapacity=1 so the emit never suspends/drops even if the
    // collector is momentarily busy.
    private val _dictatedText = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val dictatedText: kotlinx.coroutines.flow.SharedFlow<String> =
        _dictatedText.asSharedFlow()

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

    // True from a wake-initiated start() until that session's first final
    // transcript is accepted. Only the opening turn of a wake session carries
    // the wake phrase in its pre-roll; re-armed reply turns arm with
    // rewindSeconds = 0f and have nothing to verify against.
    @Volatile
    private var verifyWake: Boolean = false

    // "Let's talk" continuous mode: while true, every turn re-arms the mic
    // (no wake word) until an exit phrase, 30s silence, or an error.
    @Volatile
    private var talkMode: Boolean = false

    private val _captureMode = MutableStateFlow(CaptureMode.NORMAL)
    val captureMode: StateFlow<CaptureMode> = _captureMode.asStateFlow()

    // Whether barge-in is active for the CURRENT talk session: toggle ON and a
    // hardware echo canceller present. Computed once on entering talk mode.
    @Volatile
    private var bargeInActive: Boolean = false

    // True while Ari's TTS is actively playing in a barge-in turn — the mic is
    // armed concurrently, so a recognised final while this is set is a barge.
    @Volatile private var speaking: Boolean = false

    // The concurrent TTS job for a barge-in turn. Held so the STT collector can
    // cancel it the instant a barge final lands: tts.stop() does NOT fire the
    // utterance onDone callback, so speakAndAwait() would otherwise block on its
    // 4-15s safety timeout. Cancelling the job unblocks the speaking turn at once.
    @Volatile private var ttsJob: Job? = null

    // Bumped whenever a turn is superseded (fresh arm or dismiss); snapshotted
    // at arm time into armedGeneration. A Done whose armedGeneration no longer
    // matches turnGeneration is a straggler from a turn the session left behind.
    // A barge does NOT bump the generation, so the barge final is never dropped.
    @Volatile private var turnGeneration: Long = 0L
    @Volatile private var armedGeneration: Long = 0L

    /**
     * Begin a voice session. If one is already in progress, do nothing —
     * we don't want re-entrant sessions stomping on each other.
     *
     * [verifyWake] must be true ONLY for a wake-word detection: it subjects the
     * first final transcript to [shouldAcceptWake], which relies on the wake
     * phrase being present in the pre-roll.
     */
    fun start(verifyWake: Boolean) {
        if (sessionJob?.isActive == true) {
            Log.w(TAG, "VoiceSession.start() called while already active — ignoring")
            return
        }
        this.verifyWake = verifyWake
        // A fresh wake cancels any reply the engine was still waiting on from a
        // previous turn — the user has clearly moved on. No-op when nothing is
        // pending.
        engineHolder.peek()?.cancelPendingReply()
        awaitingReply = false
        talkMode = false
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
                    // Nothing left to verify against: the wake phrase aged out
                    // of the ring buffer with the rest of the original
                    // utterance, and the user is repeating a bare command. Same
                    // reasoning as rearmForReply — verify only what actually
                    // carries a wake phrase in its pre-roll.
                    this@VoiceSession.verifyWake = false
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
                // Fresh arm supersedes any prior turn: bump the generation and
                // snapshot it, so a straggling final from before this listen
                // (or from a turn we left behind) is detected as stale.
                turnGeneration += 1
                armedGeneration = turnGeneration

                // Track activity so we can dismiss after a silence timeout if
                // the user never actually speaks. Uses the class field so a
                // re-armed reply (rearmForReply) can refresh the window.
                lastActivityAt = System.currentTimeMillis()
                // Dismiss after a silence timeout if the user never speaks.
                // Hoisted into a local fun so it can be relaunched for a
                // re-armed reply turn (the previous one self-cancels at Done).
                fun launchSilenceWatcher(timeoutMs: Long): Job = launch {
                    while (isActive) {
                        delay(1000)
                        val idle = System.currentTimeMillis() - lastActivityAt
                        if (idle > timeoutMs) {
                            Log.i(TAG, "No speech detected within $timeoutMs ms — dismissing")
                            dismiss()
                            return@launch
                        }
                    }
                }
                // The silence watcher is held in a var so we can relaunch it for
                // a re-armed reply turn (the previous one self-cancels at Done).
                // A wake turn gets a much shorter window: you just said the wake
                // phrase, so silence means it wasn't you — and every second the
                // mic stays armed after a false accept is a second ambient
                // speech could be mistaken for a command.
                var silenceWatcher = launchSilenceWatcher(
                    if (verifyWake) WAKE_TURN_SILENCE_TIMEOUT_MS else SILENCE_TIMEOUT_MS
                )

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
                                // Snapshot the generation this final was armed
                                // under BEFORE reset() (which doesn't touch it).
                                val finalGeneration = armedGeneration
                                speechRecognizer.reset()
                                silenceWatcher.cancel()
                                if (shouldCutTts(speaking, bargeInActive)) {
                                    // User barged in: stop Ari immediately and
                                    // cancel the concurrent TTS job (tts.stop()
                                    // alone doesn't unblock speakAndAwait). This
                                    // utterance becomes the next turn — a barge
                                    // does NOT bump turnGeneration, so it isn't
                                    // stale.
                                    speechOutput.stop()
                                    speaking = false
                                    ttsJob?.cancel()
                                    ttsJob = null
                                }
                                if (isStaleTurn(finalGeneration, turnGeneration)) {
                                    // Straggler from a superseded turn (e.g. a
                                    // final that landed after dismiss()/re-arm) —
                                    // ignore it so it can't fire a phantom turn.
                                    // Keep listening if the session is still
                                    // armed for a reply; otherwise stop collecting.
                                    if (awaitingReply && isActive) {
                                        silenceWatcher = launchSilenceWatcher(SILENCE_TIMEOUT_MS)
                                    }
                                    return@collect
                                }
                                // this@ qualified throughout: the start()
                                // parameter shadows the field, and only the
                                // field is cleared once the opening turn is
                                // accepted or the mic re-arms.
                                val verify = this@VoiceSession.verifyWake
                                if (!shouldAcceptWake(verify, sttState.raw, sttState.nameMatched)) {
                                    Log.w(TAG, "Wake rejected: raw='${sttState.raw}'")
                                    dismiss()
                                    return@collect
                                }
                                this@VoiceSession.verifyWake = false
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
                                    silenceWatcher = launchSilenceWatcher(SILENCE_TIMEOUT_MS)
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
                logRepository.append(Message(text = text, isFromUser = true, source = InputSource.Voice))
                val outcome = cardActionDispatcher.dispatch(intercept.cardId, intercept.action)
                when (outcome) {
                    is dev.heyari.ari.actions.CardActionDispatcher.Outcome.Silent -> {
                        dismiss()
                        return
                    }
                    is dev.heyari.ari.actions.CardActionDispatcher.Outcome.Spoken -> {
                        if (outcome.text.isNotBlank() || outcome.attachments.isNotEmpty()) {
                            logRepository.append(
                                Message(
                                    text = outcome.text,
                                    isFromUser = false,
                                    attachments = outcome.attachments,
                                )
                            )
                        }
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

        var attachments: List<Attachment> = emptyList()
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
                val result = actionHandler.handle(response.json, response.skillId)
                attachments = result.attachments
                result.text
            }
            is FfiResponse.Binary -> "[Binary: ${response.mime}, ${response.data.size} bytes]"
            is FfiResponse.NotUnderstood -> response.body
        }

        // Does the engine want a spoken reply to this response? If so we keep
        // the mic open instead of dismissing. Computed off the final response
        // (after any parallel/offline retry reassignment).
        val enter = shouldEnterConversation(response)
        val exit = shouldExitConversation(response)
        // Re-arm if the engine wants a reply (behaviour A) OR we're in
        // continuous mode OR we're entering it.
        val rearm = shouldRearm(response) || talkMode || enter

        if (shouldPersistFacts(response)) {
            // A remember/forget command mutated the engine's fact list this
            // turn — mirror it back to disk. Read the canonical list from the
            // engine so we persist exactly what it holds. Placed BEFORE the
            // `exit` branch below (which returns early), so the write-back
            // still runs on a turn that both changes facts and exits talk mode.
            settingsRepository.setRememberedFacts(
                engineHolder.peek()?.rememberedFacts() ?: emptyList()
            )
        }

        // Log this spoken turn to the shared conversation log so it shows in
        // the chat window. Use `usedText` — the corrected transcript Ari
        // actually acted on (after the parallel/offline retry layers), not the
        // raw `text`. NotUnderstood replies are logged too, matching the text
        // path. Silent Layer-C phase-1 envelopes produce a blank responseText
        // with no attachments and are skipped, same as the text path.
        logRepository.append(Message(text = usedText, isFromUser = true, source = InputSource.Voice))
        if (responseText.isNotBlank() || attachments.isNotEmpty()) {
            logRepository.append(
                Message(text = responseText, isFromUser = false, attachments = attachments)
            )
        }

        _state.value = VoiceState.Responding(responseText)

        if (exit) {
            // Speak the ack, then leave the mode and close the session.
            speechOutput.speakAndAwait(responseText)
            talkMode = false
            bargeInActive = false
            _captureMode.value = CaptureMode.NORMAL
            engineHolder.peek()?.setConversationActive(false)
            dismiss()
            return
        }

        if (enter) {
            talkMode = true
            engineHolder.peek()?.setConversationActive(true)
            bargeInActive = bargeInEffective(
                toggleEnabled = settingsRepository.bargeInEnabled.first(),
                aecAvailable = android.media.audiofx.AcousticEchoCanceler.isAvailable(),
            )
            if (bargeInActive) _captureMode.value = CaptureMode.CONVERSATION
        }

        if (rearm && talkMode && bargeInActive) {
            // Barge-in: arm the mic NOW (the AEC removes Ari's voice from the
            // capture) and speak CONCURRENTLY. A recognised final while
            // `speaking` is set cuts Ari off — the STT collector's Done handler
            // calls speechOutput.stop() and cancels ttsJob. rearmForReply()
            // opens STT with zero rewind and bumps the turn generation.
            rearmForReply()
            speaking = true
            // Speak on the session scope, NOT inline: the collector must stay
            // free to receive the barge final. tts.stop() does not fire the
            // utterance onDone, so the Done handler cancels this job to unblock
            // the speaking turn immediately rather than waiting on the
            // speakAndAwait safety timeout.
            //
            // processInput is a blocking (non-suspend) FFI call, so on a barge
            // there's no suspension between the Done handler cutting TTS
            // (speaking=false) and the next turn's barge branch setting
            // speaking=true. The cancelled job's `finally` then runs LATER on
            // Main and would clobber the successor's speaking=true. Guard it:
            // only the job that is still the current ttsJob may clear the flag —
            // a superseded/cancelled job must not touch its successor's state.
            val thisJob = scope.launch {
                try {
                    speechOutput.speakAndAwait(responseText)
                } finally {
                    if (ownsSpeakingFlag(currentJob = ttsJob, thisJob = coroutineContext[Job])) {
                        speaking = false
                    }
                }
            }
            ttsJob = thisJob
            // No barge during TTS: STT stays armed, the silence watcher covers
            // the 30s window. The collector keeps going (awaitingReply is set).
        } else if (rearm) {
            // Turn-based (behaviour A, or talk-mode without AEC): speak first,
            // then open the mic — unchanged from today. We wait on the TTS
            // completion callback (not a guess-timer): the mic's rewind would
            // otherwise ingest the tail of Ari's own prompt and it would answer
            // its own question ("…to use, Apple Music"). rearmForReply() then
            // opens the mic with zero rewind for the same reason.
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
        // Re-arming supersedes the turn just finished: bump the generation and
        // snapshot it so any late final from the previous turn reads as stale.
        turnGeneration += 1
        armedGeneration = turnGeneration
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
     * Play the "ready" cue at full volume and return its duration in ms so the
     * caller knows how long to discard mic samples for (0 on failure). See
     * [playReadyCue] for the audio-attributes and stream rationale.
     */
    private fun startReadyCue(): Long = playReadyCue(1.0f)

    /**
     * "I'm still listening" cue played when the mic re-arms for a follow-up
     * question or a "let's talk" turn. Played a touch below the fresh cue to
     * still read as "still here" rather than "fresh start" — but clearly
     * audible (the previous 0.3 was inaudible on-device). Fire-and-forget.
     */
    private fun startListeningAgainCue() {
        playReadyCue(RE_ARM_CUE_VOLUME)
    }

    /**
     * Play [R.raw.ready] at [volume]; returns its duration in ms (0 on
     * failure). Audio attributes MUST be set BEFORE prepare: `MediaPlayer.create()`
     * prepares eagerly, so setting attributes afterwards is rejected by the
     * native layer ("trying to set audio attributes called in state 8") and
     * silently dropped — which is why the old code accidentally ran on the
     * default media stream. We now deliberately use USAGE_MEDIA: on-device A/B
     * testing showed USAGE_ASSISTANT / ASSISTANCE_SONIFICATION route to a much
     * quieter stream, too soft for a "your turn" prompt. Trade-off: the cue
     * follows the media volume.
     */
    private fun playReadyCue(volume: Float): Long = runCatching {
        val player = MediaPlayer()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        context.resources.openRawResourceFd(R.raw.ready).use { afd ->
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
        player.prepare()
        val durationMs = player.duration.toLong().coerceAtLeast(0L)
        player.setVolume(volume, volume)
        player.setOnCompletionListener { runCatching { it.release() } }
        player.setOnErrorListener { mp, what, extra ->
            Log.w(TAG, "Ready cue playback error what=$what extra=$extra")
            runCatching { mp.release() }
            true
        }
        player.start()
        durationMs
    }.getOrElse {
        Log.w(TAG, "Ready cue playback failed", it)
        0L
    }

    fun dismiss() {
        Log.i(TAG, "Dismissing voice session")
        // Drop any reply the engine was still waiting on. No-op on the normal
        // happy path (nothing pending); covers tap-dismiss, the silence-timeout
        // path (which calls dismiss()), and lifecycle stop.
        // Supersede any in-flight turn so a final that decodes after this
        // dismiss can't fire a phantom turn (isStaleTurn catches it).
        turnGeneration += 1
        speaking = false
        ttsJob?.cancel()
        ttsJob = null
        talkMode = false
        bargeInActive = false
        _captureMode.value = CaptureMode.NORMAL
        engineHolder.peek()?.setConversationActive(false)
        engineHolder.peek()?.cancelPendingReply()
        awaitingReply = false
        verifyWake = false
        speechRecognizer.stopListening()
        speechRecognizer.reset()
        speechOutput.stop()
        sessionJob?.cancel()
        sessionJob = null
        _state.value = VoiceState.Idle
    }

    /**
     * Foreground in-place dictation — STT only. No engine, no TTS, no re-arm,
     * no barge-in. Streams partials through [state] as VoiceState.Listening and
     * emits the final transcript on [dictatedText]; the caller (ConversationViewModel)
     * routes those into the composer. Reaching Idle (via [dismiss]) drives the
     * WakeWordService one-shot stand-down, exactly like a voice turn.
     */
    fun startDictation() {
        if (sessionJob?.isActive == true) {
            Log.w(TAG, "startDictation() called while a session is active — ignoring")
            return
        }
        // Non-Idle synchronously so the WakeWordService one-shot collector sees a
        // begun turn before any Idle (same reason start() does this).
        _state.value = VoiceState.Listening("")
        lastActivityAt = System.currentTimeMillis()
        sessionJob = scope.launch {
            // Bound the mic: the online/English recogniser emits Done only on a
            // stable NON-empty partial, so pure silence never endpoints. Without
            // this watcher a "tapped but never spoke" dictation would keep the
            // one-shot capture host hot forever. Mirrors start()'s silence guard.
            val silenceWatcher = launch {
                while (isActive) {
                    delay(500)
                    if (System.currentTimeMillis() - lastActivityAt > SILENCE_TIMEOUT_MS) {
                        Log.i(TAG, "Dictation: no speech within ${SILENCE_TIMEOUT_MS}ms — dismissing")
                        dismiss()
                        return@launch
                    }
                }
            }
            try {
                speechRecognizer.startListening()
                speechRecognizer.state.collect { stt ->
                    when (stt) {
                        is SttState.Listening -> {
                            if (stt.partial.isNotBlank()) lastActivityAt = System.currentTimeMillis()
                            _state.update { VoiceState.Listening(stt.partial) }
                        }
                        SttState.Transcribing -> _state.update { VoiceState.Thinking }
                        is SttState.Done -> {
                            _dictatedText.emit(stt.text)
                            dismiss()
                            return@collect
                        }
                        is SttState.Error -> {
                            Log.w(TAG, "Dictation STT error: ${stt.message}")
                            dismiss()
                            return@collect
                        }
                        SttState.Idle -> { /* ignore */ }
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "Dictation failed", t)
                dismiss()
            } finally {
                silenceWatcher.cancel()
            }
        }
    }

    /** Cancel an in-progress dictation (Stop button / lifecycle). Produces no
     *  final transcript, so nothing is submitted; the caller keeps the last
     *  partial already streamed into the field. */
    fun stopDictation() {
        if (sessionJob?.isActive != true) return
        dismiss()
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
        // The opening turn of a wake session gets a much tighter window: the
        // user just said the wake phrase, so 8 s of dead air means it wasn't
        // addressed to Ari, and every extra second the mic stays armed is a
        // second in which unrelated ambient speech can be taken as a command.
        // Re-armed reply turns and dictation keep the full 30 s above — those
        // are cases where Ari asked a question or the user deliberately opened
        // the mic, so waiting is legitimate.
        private const val WAKE_TURN_SILENCE_TIMEOUT_MS = 8_000L
        // How long to flash the corrected transcript in the overlay before
        // transitioning to the response. Long enough for the user to notice
        // the text changed, short enough not to feel like a stall.
        private const val CORRECTION_FLASH_MS = 600L

        // Re-arm cue volume: clearly audible after Ari's TTS, a touch below the
        // fresh cue's full volume. The old 0.3 was inaudible on-device.
        private const val RE_ARM_CUE_VOLUME = 0.8f
    }
}
