package dev.heyari.ari.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.R
import dev.heyari.ari.actions.ActionHandler
import dev.heyari.ari.actions.AlertVoiceIntercept
import dev.heyari.ari.actions.CardActionDispatcher
import dev.heyari.ari.actions.CardActionVoiceIntercept
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.data.card.CardAction
import dev.heyari.ari.data.conversation.ConversationLogRepository
import dev.heyari.ari.di.EngineHolder
import dev.heyari.ari.model.Attachment
import dev.heyari.ari.model.InputSource
import dev.heyari.ari.model.Message
import dev.heyari.ari.notifications.AlertService
import dev.heyari.ari.stt.SpeechRecognizer
import dev.heyari.ari.stt.SttModelLoader
import dev.heyari.ari.tts.SpeechOutput
import dev.heyari.ari.tts.pleaseRepeatPhrase
import dev.heyari.ari.tts.pleaseWaitPhrase
import dev.heyari.ari.wakeword.FalseWakeMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.FfiActionOutcome
import uniffi.ari_ffi.FfiCaptureMode
import uniffi.ari_ffi.FfiIntercepted
import uniffi.ari_ffi.FfiListenParams
import uniffi.ari_ffi.FfiLoggedTurn
import uniffi.ari_ffi.FfiPhrase
import uniffi.ari_ffi.FfiResponse
import uniffi.ari_ffi.FfiSessionConfig
import uniffi.ari_ffi.FfiSessionError
import uniffi.ari_ffi.FfiSessionHost
import uniffi.ari_ffi.FfiSessionState
import uniffi.ari_ffi.FfiSttFailure

/**
 * Possible states a voice interaction can be in. Drives the overlay UI.
 */
sealed interface VoiceState {
    data object Idle : VoiceState
    data class Preparing(val message: String) : VoiceState
    data class Listening(val partial: String) : VoiceState
    data object Thinking : VoiceState
    /** [cards] are the answer's skill cards, shown under [text]. */
    data class Responding(val text: String, val cards: List<Attachment.Card> = emptyList()) : VoiceState
    data class Error(val message: String) : VoiceState
}

/** How the shared mic is being captured. NORMAL = wake-word listening
 *  (AudioSource.VOICE_RECOGNITION); CONVERSATION = comms-mode AEC capture
 *  during a talk session (AudioSource.VOICE_COMMUNICATION). */
enum class CaptureMode { NORMAL, CONVERSATION }

/**
 * Did this turn mutate the engine's remembered-fact list (a remember/forget
 * command)? Only [FfiResponse.Text] and [FfiResponse.Action] carry the flag;
 * everything else can never change facts. For typed turns; the voice
 * session decides its own.
 */
internal fun shouldPersistFacts(response: FfiResponse): Boolean = when (response) {
    is FfiResponse.Text -> response.factsChanged
    is FfiResponse.Action -> response.factsChanged
    else -> false
}

/**
 * Did the user clear the conversation this turn ("clear history")? The engine
 * has already forgotten it; the caller clears the chat log to match.
 */
internal fun shouldClearConversation(response: FfiResponse): Boolean = when (response) {
    is FfiResponse.Text -> response.clearConversation
    is FfiResponse.Action -> response.clearConversation
    else -> false
}

/**
 * The voice session, as the phone sees it. The session itself, what a wake,
 * a tap on the mic or a dictation does from the first word to the last, is
 * the engine's (ari-voice `session`). This is its host: it shows the state
 * the engine hands it on the overlay, plays the cue, speaks, runs actions,
 * stops alerts, answers cards, writes the conversation log, and keeps an
 * answer's cards on screen for a while after it.
 *
 * A Hilt singleton, so both [dev.heyari.ari.wakeword.WakeWordService] (which
 * starts quiet wakes) and [VoiceOverlayActivity] (which renders the UI) use
 * the one session. It owns no UI: the activity observes [state] and finishes
 * itself when it returns to [VoiceState.Idle].
 */
@Singleton
class VoiceSession @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val voiceEngine: VoiceEngine,
    private val engineHolder: EngineHolder,
    private val speechRecognizer: SpeechRecognizer,
    private val sttModelLoader: SttModelLoader,
    private val speechOutput: SpeechOutput,
    private val actionHandler: ActionHandler,
    private val alertVoiceIntercept: AlertVoiceIntercept,
    private val cardActionVoiceIntercept: CardActionVoiceIntercept,
    private val cardActionDispatcher: CardActionDispatcher,
    private val settingsRepository: SettingsRepository,
    private val falseWakeMonitor: FalseWakeMonitor,
    private val logRepository: ConversationLogRepository,
) : FfiSessionHost {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val pipeline get() = voiceEngine.pipeline

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    // A dictation's transcript. extraBufferCapacity=1 so the emit never
    // suspends or drops even if the collector is momentarily busy.
    private val _dictatedText = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val dictatedText: SharedFlow<String> = _dictatedText.asSharedFlow()

    val isActive: Boolean get() = pipeline.isSessionActive()

    /**
     * Bumped by each [start] that begins a session, so an overlay can tell the
     * session it showed from a later one.
     */
    var sessionId = 0
        private set

    // An answer's cards, left on the overlay for a while after its session
    // has ended, so they can be read and their buttons used with the mic back
    // on wake duty. Cleared when the time runs out, by dismiss, and by the
    // next start.
    private val _lingering = MutableStateFlow<VoiceState.Responding?>(null)
    val lingering: StateFlow<VoiceState.Responding?> = _lingering.asStateFlow()
    private var lingerJob: Job? = null
    private val cardsLock = Any()

    // Set once the user touches an answer's cards: they then stay until closed.
    private var held = false

    private val _captureMode = MutableStateFlow(CaptureMode.NORMAL)
    val captureMode: StateFlow<CaptureMode> = _captureMode.asStateFlow()

    // The attachments of the turn being answered, from the action or card
    // that made them: the engine says when to show cards and log a reply,
    // and these are what it means.
    @Volatile
    private var turnAttachments: List<Attachment> = emptyList()

    @Volatile
    private var lastResponding: VoiceState.Responding? = null

    init {
        pipeline.attachSessionHost(this)
        // Settings the session acts on, pushed whenever one changes.
        val echoCanceller = android.media.audiofx.AcousticEchoCanceler.isAvailable()
        scope.launch {
            combine(
                listOf(
                    settingsRepository.bargeInEnabled,
                    settingsRepository.keepFalseTriggerAudio,
                    settingsRepository.keepWakeAudio,
                    settingsRepository.keepUtteranceAudio,
                    settingsRepository.keepEverythingAudio,
                    speechRecognizer.systemService.map {
                        it != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    },
                ),
            ) { v ->
                FfiSessionConfig(
                    bargeInEnabled = v[0],
                    echoCanceller = echoCanceller,
                    keepFalseTriggerAudio = v[1],
                    keepWakeAudio = v[2],
                    keepUtteranceAudio = v[3],
                    keepEverythingAudio = v[4],
                    capturesDir = context.filesDir.path,
                    hostTranscribes = v[5],
                )
            }.distinctUntilChanged().collect { pipeline.configureSession(it) }
        }
    }

    /**
     * Begin a voice session, unless one is already in progress.
     *
     * [verifyWake] is true ONLY for a wake-word detection, whose first
     * transcript must name Ari. [quiet] asks for a quiet wake, started by the
     * wake service: nothing shows until a name check has passed, so a false
     * wake shows nothing at all.
     */
    fun start(verifyWake: Boolean, quiet: Boolean = false) {
        if (!pipeline.startTurn(verifyWake, quiet)) return
        sessionId += 1
        dropCards()
    }

    /**
     * Foreground in-place dictation: speech-to-text only. Partials come
     * through [state] as [VoiceState.Listening] and the transcript on
     * [dictatedText]; the caller routes those into the composer.
     */
    fun startDictation() {
        pipeline.startDictation()
    }

    /** Cancel an in-progress dictation. No transcript, so nothing is submitted. */
    fun stopDictation() {
        pipeline.stopDictation()
    }

    /** End the session: the overlay was closed. */
    fun dismiss() {
        pipeline.dismiss()
    }

    /** The user touched the answer's cards: keep them until closed. */
    fun holdCards() {
        synchronized(cardsLock) {
            held = true
            lingerJob?.cancel()
            lingerJob = null
        }
    }

    /** Take any lingering cards off the overlay, which then closes. */
    fun dropCards() {
        synchronized(cardsLock) {
            lingerJob?.cancel()
            lingerJob = null
            held = false
            _lingering.value = null
        }
    }

    /**
     * A button pressed on a card the overlay shows, handled as the chat
     * handles one: any reply joins the conversation log and is read out.
     */
    fun onCardAction(cardId: String, action: CardAction) {
        scope.launch {
            val outcome = cardActionDispatcher.dispatch(cardId, action)
            if (outcome !is CardActionDispatcher.Outcome.Spoken) return@launch
            if (outcome.text.isBlank() && outcome.attachments.isEmpty()) return@launch
            logRepository.append(
                Message(text = outcome.text, isFromUser = false, attachments = outcome.attachments)
            )
            if (outcome.text.isNotBlank()) speechOutput.speak(outcome.text)
        }
    }

    // ── What the engine's session asks of the phone ─────────────────────

    // Set here rather than posted to the main thread: start() waits for the
    // first state, so the overlay never reads Idle and finishes itself (the
    // lock-screen race).
    override fun state(state: FfiSessionState) {
        _state.value = when (state) {
            is FfiSessionState.Idle -> {
                onIdle(state.keepCards)
                VoiceState.Idle
            }
            is FfiSessionState.Preparing -> VoiceState.Preparing(state.message)
            is FfiSessionState.Listening -> VoiceState.Listening(state.partial)
            FfiSessionState.Thinking -> {
                turnAttachments = emptyList()
                VoiceState.Thinking
            }
            is FfiSessionState.Responding -> {
                val cards = if (state.cards) turnAttachments.filterIsInstance<Attachment.Card>() else emptyList()
                VoiceState.Responding(state.text, cards).also { lastResponding = it }
            }
            is FfiSessionState.Error -> VoiceState.Error(message(state.error))
        }
    }

    /**
     * The session ended. An answer with cards leaves them on the overlay for
     * [CARD_DWELL_MS], or until closed once touched ([holdCards]).
     */
    private fun onIdle(keepCards: Boolean) {
        synchronized(cardsLock) {
            if (!keepCards) {
                lingerJob?.cancel()
                lingerJob = null
                held = false
                _lingering.value = null
                return
            }
            _lingering.value = lastResponding
            if (!held) {
                lingerJob = scope.launch {
                    delay(CARD_DWELL_MS)
                    dropCards()
                }
            }
        }
    }

    /** Words for an error, so the user is told which wall they hit. */
    private fun message(error: FfiSessionError): String = when (error) {
        FfiSessionError.NoSpeechModel -> context.getString(R.string.voice_no_speech_model)
        FfiSessionError.NoSpeech -> context.getString(R.string.stt_cloud_error_empty)
        is FfiSessionError.Failed -> error.message
        is FfiSessionError.Stt -> when (error.failure) {
            FfiSttFailure.NO_MODEL -> "No STT model loaded. Configure one in Settings."
            FfiSttFailure.BUSY -> "Audio bus busy"
            FfiSttFailure.CLOUD_NOT_CONFIGURED -> context.getString(R.string.stt_cloud_error_not_configured)
            FfiSttFailure.CLOUD_NETWORK -> context.getString(R.string.stt_cloud_error_network)
            FfiSttFailure.CLOUD_AUTH -> context.getString(R.string.stt_cloud_error_auth)
            FfiSttFailure.CLOUD_SERVER -> context.getString(R.string.stt_cloud_error_server)
            FfiSttFailure.SERVICE_REFUSED -> context.getString(R.string.stt_service_error_refused)
            FfiSttFailure.SERVICE_FAILED -> context.getString(R.string.stt_service_error_failed)
        }
    }

    /** Show a quiet wake that has passed its name check. */
    override fun reveal() {
        VoiceOverlayLauncher.launch(context, verifyWake = true)
    }

    override fun playCue(volume: Float) {
        playReadyCue(volume)
    }

    override fun speak(id: ULong, text: String, flush: Boolean) {
        val spoken = speechOutput.queueSpeech(text, flush)
        if (spoken == null) {
            pipeline.speechFinished(id)
            return
        }
        scope.launch {
            speechOutput.awaitSpoken(spoken)
            pipeline.speechFinished(id)
        }
    }

    override fun stopSpeaking() {
        speechOutput.stop()
    }

    override fun phrase(phrase: FfiPhrase): String = when (phrase) {
        FfiPhrase.PLEASE_WAIT -> pleaseWaitPhrase(context)
        FfiPhrase.PLEASE_REPEAT -> pleaseRepeatPhrase(context)
    }

    override fun captureMode(mode: FfiCaptureMode) {
        _captureMode.value = when (mode) {
            FfiCaptureMode.NORMAL -> CaptureMode.NORMAL
            FfiCaptureMode.CONVERSATION -> CaptureMode.CONVERSATION
        }
    }

    override fun loadSpeechModel(): Boolean = runBlocking {
        val model = sttModelLoader.activeDownloadedModel()
        model != null && sttModelLoader.load(model)
    }

    override fun hostListen(params: FfiListenParams) {
        speechRecognizer.listenWithService(params.rewindSeconds, params.limitMs)
    }

    override fun hostStopListen() {
        speechRecognizer.stopServiceListening()
    }

    /**
     * "Stop" over a ringing alarm silences it, ahead of everything else. The
     * words that match a button on the card on screen (Yes, No, Cancel, or
     * whatever labels the skill chose) press it, as a tap would, unless
     * [cards] is false: the engine waits on a reply to its own question. On the
     * main thread, where this ran before the session moved.
     */
    override fun intercept(text: String, cards: Boolean): FfiIntercepted = runBlocking(Dispatchers.Main) {
        alertVoiceIntercept.resolve(text)?.let { alertId ->
            logRepository.append(Message(text = text, isFromUser = true, source = InputSource.Voice))
            context.startService(AlertService.stopIntent(context, alertId))
            return@runBlocking FfiIntercepted.Alert
        }
        if (!cards) return@runBlocking FfiIntercepted.No
        val match = cardActionVoiceIntercept.resolve(text) ?: return@runBlocking FfiIntercepted.No
        logRepository.append(Message(text = text, isFromUser = true, source = InputSource.Voice))
        when (val outcome = cardActionDispatcher.dispatch(match.cardId, match.action)) {
            is CardActionDispatcher.Outcome.Silent -> FfiIntercepted.CardSilent
            is CardActionDispatcher.Outcome.Spoken -> {
                if (outcome.text.isNotBlank() || outcome.attachments.isNotEmpty()) {
                    logRepository.append(
                        Message(text = outcome.text, isFromUser = false, attachments = outcome.attachments)
                    )
                }
                turnAttachments = outcome.attachments
                FfiIntercepted.CardSpoken(outcome.text, outcome.attachments.any { it is Attachment.Card })
            }
        }
    }

    /**
     * Run an action envelope. On the session's worker, never the main thread:
     * the media path connects a MediaBrowser and waits on a latch for up to
     * 3 s, and blocking Main would ANR and deadlock the MediaBrowser callbacks.
     */
    override fun handleAction(json: String, skillId: String): FfiActionOutcome {
        val result = actionHandler.handle(json, skillId)
        turnAttachments = result.attachments
        return FfiActionOutcome(
            text = result.text,
            displayText = result.displayText,
            cards = result.attachments.any { it is Attachment.Card },
            attachments = result.attachments.isNotEmpty(),
        )
    }

    /** The spoken turn joins the conversation log, so it shows in the chat. */
    override fun turnLogged(turn: FfiLoggedTurn) {
        if (turn.clear) {
            logRepository.clear()
        } else {
            logRepository.append(Message(text = turn.heard, isFromUser = true, source = InputSource.Voice))
        }
        turn.reply?.let {
            logRepository.append(
                Message(
                    text = it.text,
                    markdown = it.markdown,
                    isFromUser = false,
                    attachments = turnAttachments,
                    skillId = it.skillId,
                )
            )
        }
    }

    /** A remember or forget command: mirror the engine's facts to disk. */
    override fun factsChanged() {
        scope.launch(Dispatchers.IO) {
            settingsRepository.setRememberedFacts(engineHolder.peek()?.rememberedFacts() ?: emptyList())
        }
    }

    override fun falseWake() {
        scope.launch { falseWakeMonitor.onFalseWake() }
    }

    override fun dictated(text: String) {
        _dictatedText.tryEmit(text)
    }

    override fun engineTurnFinished() {
        engineHolder.voiceTurnFinished()
    }

    /**
     * Play [R.raw.ready] at [volume]. Audio attributes MUST be set BEFORE
     * prepare: `MediaPlayer.create()` prepares eagerly, so setting attributes
     * afterwards is rejected by the native layer ("trying to set audio
     * attributes called in state 8") and silently dropped, which is why the
     * old code accidentally ran on the default media stream. USAGE_MEDIA on
     * purpose: on-device A/B testing showed USAGE_ASSISTANT and
     * ASSISTANCE_SONIFICATION route to a much quieter stream, too soft for a
     * "your turn" prompt. Trade-off: the cue follows the media volume.
     */
    private fun playReadyCue(volume: Float) {
        runCatching {
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
            player.setVolume(volume, volume)
            player.setOnCompletionListener { runCatching { it.release() } }
            player.setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "Ready cue playback error what=$what extra=$extra")
                runCatching { mp.release() }
                true
            }
            player.start()
        }.onFailure { Log.w(TAG, "Ready cue playback failed", it) }
    }

    companion object {
        private const val TAG = "VoiceSession"

        // How long an answer's cards stay on the overlay after it, untouched.
        private const val CARD_DWELL_MS = 10_000L
    }
}
