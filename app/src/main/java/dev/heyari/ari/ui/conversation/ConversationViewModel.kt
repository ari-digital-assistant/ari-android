package dev.heyari.ari.ui.conversation

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.heyari.ari.R
import dev.heyari.ari.actions.ActionHandler
import dev.heyari.ari.actions.AsyncEnvelopeChannel
import dev.heyari.ari.actions.CardActionDispatcher
import dev.heyari.ari.actions.CardActionVoiceIntercept
import dev.heyari.ari.actions.CardAlarmScheduler
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.listening.ListeningMode
import dev.heyari.ari.data.conversation.ConversationLogRepository
import dev.heyari.ari.data.card.Card
import dev.heyari.ari.data.card.CardAction
import dev.heyari.ari.data.card.CardStateRepository
import dev.heyari.ari.data.card.OnComplete
import dev.heyari.ari.llm.LlmDownloadManager
import dev.heyari.ari.model.Attachment
import dev.heyari.ari.model.ConversationState
import dev.heyari.ari.model.InputSource
import dev.heyari.ari.model.Message
import dev.heyari.ari.location.LocationProvider
import dev.heyari.ari.notifications.AlertAction
import dev.heyari.ari.notifications.AlertService
import dev.heyari.ari.notifications.AlertSpec
import dev.heyari.ari.stt.ModelDownloadManager
import dev.heyari.ari.stt.SpeechRecognizer
import dev.heyari.ari.stt.SttModelLoader
import dev.heyari.ari.tts.SpeechOutput
import dev.heyari.ari.tts.pleaseWaitPhrase
import dev.heyari.ari.wakeword.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import dev.heyari.ari.di.EngineHolder
import dev.heyari.ari.voice.VoiceSession
import dev.heyari.ari.voice.VoiceState
import dev.heyari.ari.voice.shouldPersistFacts
import uniffi.ari_ffi.FfiLocationStatus
import uniffi.ari_ffi.FfiResponse
import uniffi.ari_ffi.SkillRegistry
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val engineHolder: EngineHolder,
    private val speechRecognizer: SpeechRecognizer,
    private val speechOutput: SpeechOutput,
    private val sttModelLoader: SttModelLoader,
    private val downloadManager: ModelDownloadManager,
    private val llmDownloadManager: LlmDownloadManager,
    private val settingsRepository: SettingsRepository,
    private val actionHandler: ActionHandler,
    val cardRepository: CardStateRepository,
    val assetResolver: dev.heyari.ari.assets.AssetResolver,
    private val logRepository: ConversationLogRepository,
    private val cardAlarmScheduler: CardAlarmScheduler,
    private val asyncEnvelopeChannel: AsyncEnvelopeChannel,
    private val cardActionVoiceIntercept: CardActionVoiceIntercept,
    private val cardActionDispatcher: CardActionDispatcher,
    private val locationProvider: LocationProvider,
    private val voiceSession: VoiceSession,
    private val skillRegistry: SkillRegistry,
    private val reportSender: dev.heyari.ari.reporting.ReportSender,
    private val application: Application,
) : ViewModel() {

    /**
     * Sends a content report. Returns immediately — WorkManager owns the
     * delivery from here, including across a dead network and a process death.
     */
    fun sendReport(report: dev.heyari.ari.reporting.ContentReport) = reportSender.send(report)


    private val _state = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    /**
     * The voice pipeline's live phase, mapped from [VoiceSession]'s
     * [VoiceState] onto the presentation-layer [VoicePhase]. Drives the
     * ambient presence aura on the conversation screen: Listening lights it up
     * lively, Thinking/Speaking give it their own rhythms. Preparing and Error
     * fold to Idle — they're transient and shouldn't flicker the aura. The
     * typed-input "still working" flag is layered on separately in the screen
     * via [deriveAmbientState] (which also honours [ConversationState.isThinking]).
     *
     * [VoiceSession] is a Hilt singleton, so this observes the exact same state
     * the voice overlay renders — no new plumbing.
     */
    val voicePhase: StateFlow<VoicePhase> = voiceSession.state
        .map { it.toVoicePhase() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VoicePhase.Idle)

    /** The conversation log, sourced from the app-scoped repo. The screen
     *  observes this for the message list; the rest of the screen's state
     *  still comes from [state]. */
    val messages: StateFlow<List<Message>> = logRepository.messages

    private var suppressPollUntil = 0L

    /** The in-flight typed-turn coroutine, if any. Tracked so `/reset` can
     *  cancel it — "terminate the current conversation" means stopping work
     *  in progress, not just wiping the log. */
    private var activeTurn: Job? = null

    init {
        // Load active model + mark setup checked once. ensureLoaded() resolves
        // the active model and warms it (idempotent — rides the same lock as
        // AriApplication's eager load and any wake-triggered VoiceSession load).
        viewModelScope.launch(Dispatchers.IO) {
            sttModelLoader.ensureLoaded()
            _state.update { it.copy(setupChecked = true, sttReady = speechRecognizer.isModelLoaded) }
            refreshOnboarding()
        }

        // Compute the adaptive empty-state model (installed skills → chips,
        // remembered facts → greeting). Its own IO coroutine, so it can't
        // gate the STT warm-up above.
        refreshEmptyState()

        // Then keep watching for subsequent active-model changes (e.g. user
        // picks a different model in Settings). Skip the first emission so we
        // don't duplicate the load above.
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.activeSttModelId.drop(1).collect {
                sttModelLoader.ensureLoaded()
                _state.update { it.copy(sttReady = speechRecognizer.isModelLoaded) }
                refreshOnboarding()
            }
        }

        // Track background downloads so the conversation screen can show progress.
        viewModelScope.launch {
            downloadManager.state.collect { dlState ->
                _state.update { it.copy(sttDownload = dlState) }
            }
        }
        viewModelScope.launch {
            llmDownloadManager.state.collect { dlState ->
                _state.update { it.copy(llmDownload = dlState) }
            }
        }

        // Dictation: stream live partials into the input field; clear the flag
        // when the session ends (Idle/Error) — the last partial stays in the
        // field so a cancelled dictation isn't lost.
        viewModelScope.launch {
            voiceSession.state.collect { vs ->
                if (!_state.value.isDictating) return@collect
                when (vs) {
                    is VoiceState.Listening -> _state.update { it.copy(inputText = vs.partial) }
                    VoiceState.Idle -> _state.update { it.copy(isDictating = false) }
                    is VoiceState.Error -> _state.update { it.copy(isDictating = false) }
                    else -> { /* Preparing/Thinking/Responding: leave the field */ }
                }
            }
        }
        // Dictation final transcript → submit as a Voice-sourced turn. The user
        // spoke it, so it carries the mic glyph even though it flows through the
        // typed path. onTextSubmitted's own blank guard makes an empty utterance
        // a no-op.
        viewModelScope.launch {
            voiceSession.dictatedText.collect { text ->
                _state.update { it.copy(isDictating = false, inputText = text) }
                onTextSubmitted(text, InputSource.Voice)
            }
        }

        // Mirror the cloud-assistant-needed flag into UI state. The
        // hint card on the conversation screen reads this directly;
        // the flag is cleared from `selectAssistant` once the user
        // picks anything (cloud or otherwise) or by re-running
        // onboarding with a non-Cloud choice.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsRepository.pendingCloudAssistantSetup,
                settingsRepository.activeAssistantId,
            ) { pending, activeId -> pending && activeId == null }
                .collect { needs ->
                    _state.update { it.copy(needsCloudAssistantSetup = needs) }
                }
        }

        // Mirror the listening-setup-needed flag, same shape as the cloud one
        // above. Cleared from SettingsViewModel whenever a schedule/place is
        // added or the condition is unticked — see recheckPendingListeningSetup.
        viewModelScope.launch {
            settingsRepository.pendingListeningSetup.collect { needs ->
                _state.update { it.copy(needsListeningSetup = needs) }
            }
        }

        // Wake word events and STT are handled by the system overlay
        // (VoiceSession + VoiceOverlayManager) — the activity no longer
        // collects them. Keeps the activity focused on typed input + chat
        // history while voice runs entirely from the foreground service.

        // Subscribe to async envelopes the engine pushes after
        // background-threaded work (currently Layer C phase-2: skill
        // emits a consult_assistant directive, engine carries the
        // assistant round-trip, then pushes the continuation envelope
        // here). Rendered exactly like a synchronous action envelope.
        viewModelScope.launch {
            asyncEnvelopeChannel.flow.collect { pushed ->
                handlePushedEnvelope(pushed.envelopeJson, pushed.skillId)
            }
        }

        // The listening mode is a stored preference, so it can be read properly
        // rather than polled — unlike the mic state below, nothing outside Ari
        // can change it behind our back.
        viewModelScope.launch {
            settingsRepository.listeningMode.collect { mode ->
                _state.update { it.copy(listeningMode = mode) }
            }
        }

        // Poll whether the microphone is actually open. The service has its own
        // lifecycle (notification action, OS kill, a schedule boundary firing)
        // so the UI cannot rely on the last command we sent — it has to keep
        // checking what's actually true. We skip polling for a short window
        // after setListeningMode() to avoid a visible flicker while the FGS
        // finishes starting up / shutting down.
        viewModelScope.launch {
            while (isActive) {
                if (System.currentTimeMillis() >= suppressPollUntil) {
                    // A one-shot tap-to-talk run opens the mic but is NOT
                    // always-listening, so it must not light the control.
                    val hot = WakeWordService.micHot && !WakeWordService.oneShotActive
                    if (hot != _state.value.isListening) {
                        _state.update { it.copy(isListening = hot) }
                    }
                }
                delay(1000)
            }
        }
    }

    fun onInputChanged(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    /**
     * `/reset` / `/clear` — terminate the current conversation and return the screen to
     * the state a user sees on opening Ari: cancel any in-flight typed turn,
     * clear the conversation log (so the adaptive empty state renders again),
     * and clear the input + transient thinking indicator. Live cards/alarms
     * are background state and deliberately left running.
     */
    private fun handleReset() {
        activeTurn?.cancel()
        logRepository.clear()
        _state.update { it.copy(inputText = "", isThinking = false, wakeWordDetected = false) }
    }

    fun onTextSubmitted(text: String, source: InputSource = InputSource.Text) {
        if (text.isBlank()) return

        // Debug hook: `/card-demo <secs> [name]` synthesises a fake card +
        // alert into the repo so we can exercise the rendering and alert
        // flow without needing a skill installed. Useful for the first
        // post-refactor smoke; replaceable once the timer skill itself is
        // emitting the new envelope.
        if (text.startsWith("/card-demo")) {
            handleCardDemo(text)
            return
        }
        if (text.startsWith("/alert-demo")) {
            handleAlertDemo(text)
            return
        }
        if (text.startsWith("/location")) {
            handleLocationDebug(text)
            return
        }
        // `/reset` (or `/clear`) — clear the conversation and return to the
        // opening screen.
        if (text.startsWith("/reset") || text.startsWith("/clear")) {
            handleReset()
            return
        }

        val userMessage = Message(text = text, isFromUser = true, source = source)
        logRepository.append(userMessage)
        _state.update { it.copy(inputText = "", wakeWordDetected = false) }

        // If the most recent active card has a button whose id or
        // label matches the user's word, dispatch that button as if
        // tapped — short-circuits the engine so "yes" / "no" /
        // "cancel" / "keep" answer a clarification card naturally
        // without the assistant ever seeing the word. Skill-agnostic:
        // any card with actions inherits this UX for free.
        cardActionVoiceIntercept.resolve(text)?.let { match ->
            onCardAction(match.cardId, match.action)
            return
        }

        activeTurn = viewModelScope.launch(Dispatchers.Default) {
            // "Thinking" is signalled on two INDEPENDENT channels:
            //
            //  - Visual (IMMEDIATE): `isThinking` drives the ambient
            //    border/aura + the transient ThinkingIndicator dots from the
            //    moment the turn starts, so every query gets instant feedback —
            //    not only ones slower than the 4s threshold. UI-only; NEVER
            //    appended to the conversation log, so it can't survive into
            //    the record.
            //  - Spoken (GATED at STILL_WORKING_DELAY_MS): the "please wait"
            //    phrase is only spoken if processInput blocks past the
            //    threshold — we mustn't talk over a fast answer, but this is
            //    the only eyes-free cue in a background/voice-only session, so
            //    it must survive for slow turns.
            //
            // Both are torn down in the finally.
            _state.update { it.copy(isThinking = true) }
            val fillerJob = launch {
                delay(STILL_WORKING_DELAY_MS)
                // Same shared "please wait" vocabulary the STT warm-up uses, so
                // the two slow paths feel like one feature.
                speechOutput.speak(pleaseWaitPhrase(application))
            }

            val response = try {
                engineHolder.processInput(text)
            } finally {
                fillerJob.cancel()
                _state.update { it.copy(isThinking = false) }
            }

            // Personal memory: if this turn captured/forgot a fact, mirror the
            // engine's updated fact list to disk. The text-chat path needs this
            // just like VoiceSession does — without it, facts typed here live
            // only in the engine's RAM and never reach the settings screen
            // (which reads the persisted store). Runs before any early return.
            if (shouldPersistFacts(response)) {
                settingsRepository.setRememberedFacts(
                    engineHolder.peek()?.rememberedFacts() ?: emptyList()
                )
                // A captured name (or any new fact) should light up the greeting
                // the instant the user returns to an empty view, not only on the
                // next resume.
                refreshEmptyState()
            }

            var attachments: List<Attachment> = emptyList()
            // Only action responses carry an id; a plain Text answer is
            // unattributable, and a report on one says so rather than guessing.
            var skillId: String? = null
            // What the bubble shows, when a skill wants it worded differently
            // from what Ari says out loud. Null everywhere else.
            var bubbleText: String? = null
            val responseText = when (response) {
                is FfiResponse.Text -> response.body
                is FfiResponse.Action -> {
                    val result = actionHandler.handle(response.json, response.skillId)
                    attachments = result.attachments
                    skillId = response.skillId.takeIf { it.isNotBlank() }
                    bubbleText = result.displayText
                    result.text
                }
                is FfiResponse.Binary -> "[Binary: ${response.mime}, ${response.data.size} bytes]"
                // Text-input path: no STT to retry, so NotUnderstood is just
                // the apology body as-is.
                is FfiResponse.NotUnderstood -> response.body
            }

            // Skip rendering an empty bubble. Layer C phase-1 envelopes
            // are deliberately silent (no speak, no cards) so the
            // assistant round-trip can run quietly; only the phase-2
            // result and any delay phrase render as bubbles. Without
            // this skip we'd flash a ghost bubble per phase-1.
            if (responseText.isBlank() && attachments.isEmpty()) return@launch

            val ariMessage = Message(
                text = bubbleText ?: responseText,
                isFromUser = false,
                attachments = attachments,
                skillId = skillId,
            )
            logRepository.append(ariMessage)

            // Speak text and action confirmations alike — both are just user-facing strings now
            if (response is FfiResponse.Text || response is FfiResponse.Action || response is FfiResponse.NotUnderstood) {
                if (responseText.isNotBlank()) speechOutput.speak(responseText)
            }
        }
    }

    private companion object {
        /** How long processInput can block before we surface a "still working" bubble. */
        const val STILL_WORKING_DELAY_MS: Long = 4000
    }

    /**
     * Handle an envelope pushed to [AsyncEnvelopeChannel] from the
     * engine's background thread (currently only Layer C phase-2
     * continuation envelopes). Parse via [ActionHandler] identically
     * to a synchronous `FfiResponse.Action`, then append as a new
     * assistant message and speak the text. The skill id parameter
     * lets the action handler resolve `asset:` references back to the
     * emitting skill's bundle.
     *
     * Runs on the viewmodel scope; suspendable action-handler work
     * (TTS, card writes, etc.) awaits naturally.
     */
    private suspend fun handlePushedEnvelope(envelopeJson: String, skillId: String?) {
        val result = actionHandler.handle(envelopeJson, skillId ?: "")
        val message = Message(
            text = result.bubbleText,
            isFromUser = false,
            attachments = result.attachments,
            skillId = skillId?.takeIf { it.isNotBlank() },
        )
        logRepository.append(message)
        if (result.text.isNotBlank()) speechOutput.speak(result.text)
    }

    /**
     * `/card-demo 30 pasta` — synthesise a card with a 30s countdown and
     * an `on_complete.alert` directly into the repo, bypassing the skill.
     * Lets us exercise the new presentation pipeline (card render, alarm
     * fire, alert loop) without waiting for the timer skill rewrite.
     */
    private fun handleCardDemo(raw: String) {
        val parts = raw.trim().split(Regex("\\s+"))
        val durSecs = parts.getOrNull(1)?.let { parseDurationToSecs(it) } ?: 30L
        val name = parts.getOrNull(2)
        val now = System.currentTimeMillis()
        val cardId = "card_demo-$now"
        val alertId = "alert_demo-$now"
        val title = name?.let { "${capitaliseFirst(it)} timer" } ?: "Timer"
        val card = Card(
            id = cardId,
            // demo skill id has no install dir → asset references won't resolve;
            // GenericCard tolerates that and renders without an icon.
            skillId = "demo.local",
            title = title,
            subtitle = null,
            body = null,
            icon = null,
            countdownToTsMs = now + durSecs * 1000,
            startedAtTsMs = now,
            progress = null,
            accent = Card.Accent.DEFAULT,
            // Mirror the real timer skill's Cancel button so /card-demo
            // exercises the same action-row path. Demo cards live outside
            // the skill system, so the tap just locally drops the card —
            // see the `card_demo-` branch in onCardAction.
            actions = listOf(
                CardAction(
                    id = "cancel",
                    label = "Cancel",
                    utterance = "cancel demo timer",
                    speak = null,
                    style = CardAction.Style.DESTRUCTIVE,
                ),
            ),
            onComplete = OnComplete(
                alert = AlertSpec(
                    id = alertId,
                    skillId = "demo.local",
                    title = "$title done",
                    body = null,
                    urgency = AlertSpec.Urgency.CRITICAL,
                    sound = AlertSpec.SoundToken.ALARM,
                    speechLoop = title,
                    autoStopMs = 120_000L,
                    maxCycles = 12,
                    fullTakeover = true,
                    actions = listOf(
                        AlertAction(
                            id = "stop_alert",
                            label = "Stop",
                            utterance = null,
                            style = AlertAction.Style.PRIMARY,
                        ),
                    ),
                    icon = null,
                ),
                dismissCard = true,
                dismissNotificationIds = emptyList(),
            ),
            onCancel = null,
        )
        cardRepository.debugInsertCard(card)
        cardAlarmScheduler.schedule(card)

        val userMessage = Message(text = raw, isFromUser = true)
        val ariMessage = Message(
            text = "Demo card injected: ${name ?: "anonymous"}, ${durSecs}s.",
            isFromUser = false,
            attachments = listOf(Attachment.Card(cardId)),
        )
        logRepository.append(userMessage)
        logRepository.append(ariMessage)
        _state.update { it.copy(inputText = "") }
    }

    /**
     * `/alert-demo [name]` — fire a critical, full-takeover alert right now,
     * bypassing the countdown. Also explicitly starts [dev.heyari.ari.notifications.AlertActivity]
     * from the foreground so the lock-screen-style takeover UI is visible
     * immediately — without this, Android's FSN heuristic suppresses the
     * full-screen activity in favour of a heads-up whenever the emitting
     * app is already top-of-stack (which we are, we're running the VM).
     * The debugger doesn't need to lock the screen to see the takeover.
     */
    private fun handleAlertDemo(raw: String) {
        val parts = raw.trim().split(Regex("\\s+"))
        val name = parts.getOrNull(1)
        val now = System.currentTimeMillis()
        val alertId = "alert_demo-$now"
        val title = name?.let { "${capitaliseFirst(it)} timer done" } ?: "Timer done"
        val speech = name?.let { "${capitaliseFirst(it)} timer" }
        val spec = AlertSpec(
            id = alertId,
            skillId = "demo.local",
            title = title,
            body = null,
            urgency = AlertSpec.Urgency.CRITICAL,
            sound = AlertSpec.SoundToken.ALARM,
            speechLoop = speech,
            autoStopMs = 120_000L,
            maxCycles = 12,
            fullTakeover = true,
            actions = listOf(
                AlertAction(
                    id = "stop_alert",
                    label = "Stop",
                    utterance = null,
                    style = AlertAction.Style.PRIMARY,
                ),
            ),
            icon = null,
        )
        application.startForegroundService(AlertService.startIntent(application, spec))
        application.startActivity(dev.heyari.ari.notifications.AlertActivity.intent(application, spec))

        val userMessage = Message(text = raw, isFromUser = true)
        val ariMessage = Message(
            text = "Demo alert firing: ${name ?: "anonymous"}.",
            isFromUser = false,
        )
        logRepository.append(userMessage)
        logRepository.append(ariMessage)
        _state.update { it.copy(inputText = "") }
    }

    /**
     * `/location` — debug hook: calls the coarse [LocationProvider] (the
     * Android impl behind the `location` host capability) and prints the
     * raw fix / status into the chat. Lets us smoke-test the on-device
     * FusedLocation path — real fix, coarse-permission gating, services-off
     * → UNAVAILABLE — without a location-using skill installed. Mirrors
     * /card-demo and /alert-demo; remove once the weather skill exercises
     * the capability end-to-end. The provider call blocks (Tasks.await), so
     * it runs off the main thread.
     *
     * Prefers a fresh fix (`maxAgeMs = 0`) so the probe reflects the
     * device's current position rather than echoing a cached one. Emulators
     * often can't produce a fresh coarse fix (no live network provider), so
     * on timeout it falls back to last-known and labels it `[cached]` rather
     * than reporting a useless timeout. Real skills use the 10-minute cached
     * default directly.
     */
    private fun handleLocationDebug(raw: String) {
        val userMessage = Message(text = raw, isFromUser = true)
        logRepository.append(userMessage)
        _state.update { it.copy(inputText = "") }

        viewModelScope.launch(Dispatchers.IO) {
            var r = locationProvider.current(maxAgeMs = 0L, timeoutMs = 5_000L)
            var cached = false
            if (r.status == FfiLocationStatus.TIMEOUT) {
                val lastKnown = locationProvider.current(maxAgeMs = Long.MAX_VALUE, timeoutMs = 5_000L)
                if (lastKnown.status == FfiLocationStatus.OK) {
                    r = lastKnown
                    cached = true
                }
            }
            val text = when (r.status) {
                FfiLocationStatus.OK -> {
                    val ageSeconds = (System.currentTimeMillis() - r.timestampMs) / 1000
                    val suffix = if (cached) "  [cached — no fresh fix available]" else ""
                    String.format(
                        Locale.US,
                        "📍 %.5f, %.5f  (±%.0f m, fix age %ds)%s",
                        r.lat,
                        r.lon,
                        r.accuracyM,
                        ageSeconds,
                        suffix,
                    )
                }
                FfiLocationStatus.PERMISSION_DENIED ->
                    "Location permission not granted — enable it in Settings → Permissions."
                FfiLocationStatus.UNAVAILABLE ->
                    "Location unavailable — services off or Google Play Services missing."
                FfiLocationStatus.TIMEOUT ->
                    "Timed out waiting for a location fix (no cached fix either)."
            }
            val ariMessage = Message(text = text, isFromUser = false)
            logRepository.append(ariMessage)
        }
    }

    private fun capitaliseFirst(s: String): String =
        if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)

    private fun parseDurationToSecs(raw: String): Long {
        // "30", "30s", "5m", "1h", "1h30m" — tolerant of the common forms.
        var total = 0L
        var number = 0L
        var sawDigit = false
        for (ch in raw) {
            when {
                ch.isDigit() -> {
                    number = number * 10 + (ch - '0')
                    sawDigit = true
                }
                ch == 's' || ch == 'm' || ch == 'h' -> {
                    val mult = when (ch) { 's' -> 1L; 'm' -> 60L; else -> 3600L }
                    total += number * mult
                    number = 0
                    sawDigit = false
                }
                else -> return 30L
            }
        }
        // Trailing bare digits default to seconds.
        if (sawDigit) total += number
        return total.coerceAtLeast(1L)
    }

    /**
     * A user tapped an action button on a card. Reserved ids short-circuit
     * locally; everything else routes through the engine via the action's
     * `utterance`, which the skill handles like any other input. The
     * resulting envelope flows back through the same processInput path
     * and reconciles state.
     */
    fun onCardAction(cardId: String, action: CardAction) {
        viewModelScope.launch(Dispatchers.Default) {
            when (val outcome = cardActionDispatcher.dispatch(cardId, action)) {
                is CardActionDispatcher.Outcome.Silent -> Unit
                is CardActionDispatcher.Outcome.Spoken -> {
                    if (outcome.text.isBlank() && outcome.attachments.isEmpty()) return@launch
                    val ariMessage = Message(
                        text = outcome.text,
                        isFromUser = false,
                        attachments = outcome.attachments,
                    )
                    logRepository.append(ariMessage)
                    if (outcome.text.isNotBlank()) speechOutput.speak(outcome.text)
                }
            }
        }
    }

    fun syncServiceState() {
        // Exclude a transient one-shot tap-to-talk run — it keeps the service
        // alive but is not always-listening (mirrors the poll loop above).
        _state.update {
            it.copy(isListening = WakeWordService.micHot && !WakeWordService.oneShotActive)
        }
        refreshOnboarding()
        // Re-derive the empty state so installing a skill or teaching Ari
        // your name (both possible while we were away) is reflected on return.
        refreshEmptyState()
    }

    /**
     * Recompute the adaptive empty-state model off the main thread and
     * push it into [ConversationState]. Reads the installed skills and each
     * one's declared example utterances (generically — no skill is named or
     * special-cased here), plus the remembered facts to detect the user's
     * name. Every registry read is wrapped so a single bad manifest can't
     * crash the home screen. Maps through the pure functions in
     * [EmptyStateLogic] and updates [ConversationState.emptyMode] /
     * [ConversationState.greeting] / [ConversationState.suggestionChips].
     */
    private fun refreshEmptyState() {
        viewModelScope.launch(Dispatchers.IO) {
            val installed = runCatching { skillRegistry.listInstalled() }.getOrDefault(emptyList())
            val locale = Locale.getDefault().language
            val examples = installed.map { s ->
                runCatching {
                    skillRegistry.readInstalledManifest(s.id, locale).examples
                }.getOrDefault(emptyList())
            }
            val facts = settingsRepository.rememberedFactsOnce()
            val name = detectUserName(facts)
            val rememberChip =
                if (name == null) application.getString(R.string.empty_chip_remember_name) else null
            val chips = assembleChips(examples, rememberChip, max = 4)
            val hour = java.time.LocalTime.now().hour
            _state.update {
                it.copy(
                    emptyMode = emptyStateMode(installed.size),
                    greeting = greetingModel(name, hour),
                    suggestionChips = chips,
                )
            }
        }
    }

    private fun refreshOnboarding() {
        // If the user completed (or skipped) the onboarding wizard, they've
        // made their choices. Don't nag them with the setup card.
        val onboardingDone = kotlinx.coroutines.runBlocking {
            settingsRepository.onboardingCompleted.first()
        }
        if (onboardingDone) {
            _state.update { it.copy(needsSetup = false) }
            return
        }

        val hasMic = ContextCompat.checkSelfPermission(
            application, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasModel = speechRecognizer.isModelLoaded
        // SAW is required for the wake word to keep working over the lock screen
        // on every detection (it grants UID-wide Background Activity Launch
        // privilege — Ari does not actually draw any overlay windows).
        val hasOverlay = Settings.canDrawOverlays(application)
        val needs = !hasMic || !hasModel || !hasOverlay
        _state.update {
            // Don't flash the card before startup checks have completed
            it.copy(needsSetup = if (it.setupChecked) needs else false)
        }
    }

    /**
     * Start a one-off voice turn from a foreground user action (the composer's
     * mic button) — the tap-to-talk equivalent of saying "Hey Ari". Two cases,
     * both routed through [WakeWordService] so the overlay-launch path is shared
     * with the wake detection path:
     *
     *  - Always-listening ON ([WakeWordService.isRunning]): the service already
     *    owns an open mic + CaptureBus, so we just tell it to trigger a turn NOW
     *    (EXTRA_ONE_SHOT = false). It keeps listening for wake words afterwards.
     *  - Always-listening OFF: start the service as a transient capture host in
     *    one-shot mode (EXTRA_ONE_SHOT = true). It opens the mic, triggers the
     *    turn immediately, and stands itself down once the turn returns to Idle
     *    so the mic doesn't stay hot.
     *
     * Callers MUST have RECORD_AUDIO granted first — the screen routes through
     * the shared permission launcher, exactly like the wake switch.
     */
    fun startVoiceTurn() {
        // Ignore repeat taps once a turn is already running, so a double-tap
        // can't re-deliver onStartCommand or relaunch the overlay. Covers the
        // common case where the overlay is already up (isActive) AND the brief
        // window where a one-shot host is up but its overlay hasn't started the
        // session yet (oneShotActive).
        if (voiceSession.isActive || WakeWordService.oneShotActive) return

        // Sticky against an in-progress one-shot: a second tap that lands after
        // the one-shot host is up (isRunning flips true) must NOT send
        // EXTRA_ONE_SHOT=false — that would overwrite oneShotActive on the very
        // run that IS the one-shot host and strand a hot mic. oneShotActive kept
        // in the OR so the transient host stays flagged one-shot.
        val oneShot = !WakeWordService.isRunning || WakeWordService.oneShotActive
        WakeWordService.start(application) {
            action = WakeWordService.ACTION_START_VOICE_TURN
            putExtra(WakeWordService.EXTRA_ONE_SHOT, oneShot)
        }
    }

    /**
     * Foreground composer dictation. Mirrors [startVoiceTurn]'s hardened guard +
     * one-shot computation, but routes to ACTION_START_DICTATION (STT-only, no
     * overlay). Gated by the caller on sttReady; guarded here against an active
     * wake turn (the CaptureBus is single-consumer).
     */
    fun startDictation() {
        if (voiceSession.isActive || WakeWordService.oneShotActive) return
        if (!speechRecognizer.isModelLoaded) return
        _state.update { it.copy(isDictating = true) }
        val oneShot = !WakeWordService.isRunning || WakeWordService.oneShotActive
        WakeWordService.start(application) {
            action = WakeWordService.ACTION_START_DICTATION
            putExtra(WakeWordService.EXTRA_ONE_SHOT, oneShot)
        }

        // Safety net: if the host never brings the session up (FGS blocked,
        // model unloaded), no Idle transition arrives to clear the flag. Clear
        // it if the dictation session hasn't become active shortly. A running
        // session keeps voiceSession.isActive true, so this never fires on a
        // valid (even slow-to-start) dictation.
        viewModelScope.launch {
            delay(4000)
            if (_state.value.isDictating && !voiceSession.isActive) {
                _state.update { it.copy(isDictating = false) }
            }
        }
    }

    /** Stop button: cancel dictation, keep the partial already in the field. */
    fun stopDictation() {
        voiceSession.stopDictation()
        _state.update { it.copy(isDictating = false) }
    }

    /**
     * The top-bar mode switch.
     *
     * Moving off [ListeningMode.NEVER] has to start the service from here, and
     * here specifically: this is a foreground tap, and a foreground tap is one
     * of the very few ways Android 14+ permits a `microphone` foreground
     * service to be started at all. Moving to NEVER only writes the
     * preference — the service is collecting the listening policy, sees the
     * resulting Off, and stands itself down.
     */
    fun setListeningMode(mode: ListeningMode) {
        viewModelScope.launch {
            settingsRepository.setListeningMode(mode)
        }
        if (mode != ListeningMode.NEVER && !WakeWordService.isRunning) {
            WakeWordService.start(application)
        }
        // Suppress the poll loop briefly while the FGS finishes its lifecycle
        // transition, otherwise the user sees a flicker.
        suppressPollUntil = System.currentTimeMillis() + 2500
        // CUSTOM's actual hot/cold state depends on conditions we can't
        // resolve here — leave isListening alone and let the poll loop settle
        // it rather than flash a guess.
        val optimisticListening = when (mode) {
            ListeningMode.NEVER -> false
            ListeningMode.ALWAYS -> true
            ListeningMode.CUSTOM -> _state.value.isListening
        }
        _state.update { it.copy(listeningMode = mode, isListening = optimisticListening) }
    }

}
