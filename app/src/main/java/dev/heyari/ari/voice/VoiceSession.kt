package dev.heyari.ari.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.R
import dev.heyari.ari.actions.ActionHandler
import dev.heyari.ari.actions.CardActionDispatcher
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.data.card.CardAction
import dev.heyari.ari.data.card.CardStateRepository
import dev.heyari.ari.data.conversation.ConversationLogRepository
import dev.heyari.ari.di.EngineHolder
import dev.heyari.ari.model.Attachment
import dev.heyari.ari.model.InputSource
import dev.heyari.ari.model.Message
import dev.heyari.ari.notifications.AlertRegistry
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
import uniffi.ari_ffi.FfiAlertTarget
import uniffi.ari_ffi.FfiCaptureMode
import uniffi.ari_ffi.FfiCardButton
import uniffi.ari_ffi.FfiCardPressed
import uniffi.ari_ffi.FfiCardTarget
import uniffi.ari_ffi.FfiInterceptTargets
import uniffi.ari_ffi.FfiListenParams
import uniffi.ari_ffi.FfiLoggedTurn
import uniffi.ari_ffi.FfiPhrase
import uniffi.ari_ffi.FfiSessionConfig
import uniffi.ari_ffi.FfiSessionError
import uniffi.ari_ffi.FfiSessionHost
import uniffi.ari_ffi.FfiSessionState
import uniffi.ari_ffi.FfiSttFailure
import uniffi.ari_ffi.FfiTypedFinish

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
 * What a typed turn in the engine tells the chat. [turn] tells concurrent
 * turns apart.
 */
sealed interface TypedEvent {
    val turn: ULong

    /** The words have gone to the engine. */
    data class Thinking(override val turn: ULong) : TypedEvent

    /** An on-device answer while it is written, each replacing the last. */
    data class AnswerText(override val turn: ULong, val soFar: String, val markdown: String?) : TypedEvent

    /**
     * The turn is over. [clear]: empty the log, request included, first.
     * [reply] takes the place of the answer shown while it was written, or
     * is added when there was none.
     */
    data class Finished(override val turn: ULong, val clear: Boolean, val reply: Message?) : TypedEvent
}

/**
 * The voice session, as the phone sees it. The session itself, what a wake,
 * a tap on the mic or a dictation does from the first word to the last, is
 * the engine's (ari-voice `session`), and so are turns typed into the chat.
 * This is its host: it shows the state the engine hands it on the overlay,
 * plays the cue, speaks, runs actions, stops alerts, answers cards, writes
 * the conversation log, keeps an answer's cards on screen for a while after
 * it, and passes typed turns' answers to the chat ([typedEvents]).
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
    private val cardRepository: CardStateRepository,
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

    // Unlimited, so the engine's thread never waits on the chat.
    private val _typedEvents = MutableSharedFlow<TypedEvent>(extraBufferCapacity = Int.MAX_VALUE)
    val typedEvents: SharedFlow<TypedEvent> = _typedEvents.asSharedFlow()

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

    /**
     * A turn typed into the chat, or dictated into it ([spoken]: Ari says the
     * answer). The chat shows the words itself; the rest comes on [typedEvents].
     */
    fun submitText(text: String, spoken: Boolean) {
        pipeline.submitText(text, spoken)
    }

    /** The chat was reset: typed turns in flight say and log nothing more. */
    fun cancelTyped() {
        pipeline.cancelTyped()
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
     * The alerts sounding and the cards with their buttons. The engine matches
     * what was said against them ("stop", or a button's label) and calls
     * [stopAlert] or [pressCardButton].
     */
    override fun interceptTargets(): FfiInterceptTargets = FfiInterceptTargets(
        alerts = AlertRegistry.sounding().map { alert ->
            FfiAlertTarget(id = alert.id, stopLabel = alert.actions.firstOrNull { it.id == STOP_ALERT_ACTION }?.label)
        },
        cards = cardRepository.state.value.map { card ->
            FfiCardTarget(id = card.id, buttons = card.actions.map { FfiCardButton(id = it.id, label = it.label) })
        },
    )

    override fun stopAlert(alertId: String) {
        context.startService(AlertService.stopIntent(context, alertId))
    }

    /**
     * Press a card's button, as a tap would. On the main thread, where this
     * ran before the session moved. The engine logs what was said and answered.
     */
    override fun pressCardButton(cardId: String, buttonId: String): FfiCardPressed = runBlocking(Dispatchers.Main) {
        val action = cardRepository.state.value.firstOrNull { it.id == cardId }
            ?.actions?.firstOrNull { it.id == buttonId }
            ?: return@runBlocking FfiCardPressed.Gone
        when (val outcome = cardActionDispatcher.dispatch(cardId, action)) {
            is CardActionDispatcher.Outcome.Silent -> FfiCardPressed.Silent
            is CardActionDispatcher.Outcome.Spoken -> {
                turnAttachments = outcome.attachments
                FfiCardPressed.Spoken(
                    text = outcome.text,
                    cards = outcome.attachments.any { it is Attachment.Card },
                    attachments = outcome.attachments.isNotEmpty(),
                )
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

    override fun typedThinking(turn: ULong) {
        // A fresh turn: no attachments until its action or card makes some.
        turnAttachments = emptyList()
        _typedEvents.tryEmit(TypedEvent.Thinking(turn))
    }

    override fun answerText(turn: ULong, soFar: String, markdown: String?) {
        _typedEvents.tryEmit(TypedEvent.AnswerText(turn, soFar, markdown))
    }

    override fun typedFinished(turn: ULong, finish: FfiTypedFinish) {
        val reply = finish.reply?.let {
            Message(
                text = it.text,
                markdown = it.markdown,
                isFromUser = false,
                attachments = turnAttachments,
                skillId = it.skillId,
            )
        }
        _typedEvents.tryEmit(TypedEvent.Finished(turn, finish.clear, reply))
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

        // The reserved id of an alert's Stop button.
        private const val STOP_ALERT_ACTION = "stop_alert"
    }
}
