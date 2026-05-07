package dev.heyari.ari.ui.conversation

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.heyari.ari.actions.ActionHandler
import dev.heyari.ari.actions.AsyncEnvelopeChannel
import dev.heyari.ari.actions.CardActionDispatcher
import dev.heyari.ari.actions.CardActionVoiceIntercept
import dev.heyari.ari.actions.CardAlarmScheduler
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.data.card.Card
import dev.heyari.ari.data.card.CardAction
import dev.heyari.ari.data.card.CardStateRepository
import dev.heyari.ari.data.card.OnComplete
import dev.heyari.ari.llm.LlmDownloadManager
import dev.heyari.ari.model.Attachment
import dev.heyari.ari.model.ConversationState
import dev.heyari.ari.model.Message
import dev.heyari.ari.notifications.AlertAction
import dev.heyari.ari.notifications.AlertService
import dev.heyari.ari.notifications.AlertSpec
import dev.heyari.ari.stt.ModelDownloadManager
import dev.heyari.ari.stt.SpeechRecognizer
import dev.heyari.ari.stt.SttModelRegistry
import dev.heyari.ari.tts.SpeechOutput
import dev.heyari.ari.wakeword.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.ari_ffi.AriEngine
import uniffi.ari_ffi.FfiResponse
import javax.inject.Inject

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val engine: AriEngine,
    private val speechRecognizer: SpeechRecognizer,
    private val speechOutput: SpeechOutput,
    private val downloadManager: ModelDownloadManager,
    private val llmDownloadManager: LlmDownloadManager,
    private val settingsRepository: SettingsRepository,
    private val actionHandler: ActionHandler,
    val cardRepository: CardStateRepository,
    val assetResolver: dev.heyari.ari.assets.AssetResolver,
    private val cardAlarmScheduler: CardAlarmScheduler,
    private val asyncEnvelopeChannel: AsyncEnvelopeChannel,
    private val cardActionVoiceIntercept: CardActionVoiceIntercept,
    private val cardActionDispatcher: CardActionDispatcher,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private var suppressPollUntil = 0L

    init {
        // Load active model + mark setup checked once. Done as a launch-and-await sequence
        // so the onboarding flag flips correctly only after the model has had a chance to load.
        viewModelScope.launch(Dispatchers.IO) {
            val activeId = settingsRepository.activeSttModelId.first()
            val model = SttModelRegistry.byId(activeId)
            if (model != null && downloadManager.isDownloaded(model) && speechRecognizer.currentModelId != model.id) {
                runCatching {
                    speechRecognizer.loadModel(model, downloadManager.modelDir(model))
                }
            }
            _state.update { it.copy(setupChecked = true) }
            refreshOnboarding()
        }

        // Then keep watching for subsequent active-model changes (e.g. user picks a
        // different model in Settings). Skip the very first emission so we don't
        // duplicate work the block above just did.
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.activeSttModelId.drop(1).collect { activeId ->
                val model = SttModelRegistry.byId(activeId)
                if (model != null && downloadManager.isDownloaded(model) && speechRecognizer.currentModelId != model.id) {
                    runCatching {
                        speechRecognizer.loadModel(model, downloadManager.modelDir(model))
                    }
                }
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

        // Poll the wake word service state every second. The service has its own
        // lifecycle (notification action, OS kill, etc.) so the UI cannot rely on
        // the last command we sent — it has to keep checking what's actually true.
        // We skip polling for a short window after setWakeWordEnabled() to avoid
        // a visible flicker while the FGS finishes starting up / shutting down.
        viewModelScope.launch {
            while (isActive) {
                if (System.currentTimeMillis() >= suppressPollUntil) {
                    val running = WakeWordService.isRunning
                    if (running != _state.value.isListening) {
                        _state.update { it.copy(isListening = running) }
                    }
                }
                delay(1000)
            }
        }
    }

    fun onInputChanged(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun onTextSubmitted(text: String) {
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

        val userMessage = Message(text = text, isFromUser = true)
        _state.update { it.copy(messages = it.messages + userMessage, inputText = "", wakeWordDetected = false) }

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

        viewModelScope.launch(Dispatchers.Default) {
            // Generic "still working" filler. If processInput hasn't
            // returned within STILL_WORKING_DELAY_MS, push a
            // placeholder bubble so the user knows Ari is still on
            // it instead of staring at silence. Same UX shape as
            // Layer C's delay phrase but applies to ANY slow path —
            // built-in LLM QA, cloud assistant, slow skill, anything
            // that makes processInput block past the threshold.
            // Cancelled when processInput returns; if the bubble was
            // already emitted, the response renders as a fresh
            // assistant message below it.
            val fillerJob = launch {
                delay(STILL_WORKING_DELAY_MS)
                val filler = Message(
                    text = STILL_WORKING_PHRASE,
                    isFromUser = false,
                )
                _state.update { it.copy(messages = it.messages + filler) }
                // Match Layer C's behaviour: the delay phrase is spoken
                // as well as shown so the user gets audible confirmation
                // that Ari is still working, particularly useful in
                // hands-free / driving contexts.
                speechOutput.speak(STILL_WORKING_PHRASE)
            }

            val response = try {
                engine.processInput(text)
            } finally {
                fillerJob.cancel()
            }

            var attachments: List<Attachment> = emptyList()
            val responseText = when (response) {
                is FfiResponse.Text -> response.body
                is FfiResponse.Action -> {
                    val result = actionHandler.handle(response.json, response.skillId)
                    attachments = result.attachments
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
                text = responseText,
                isFromUser = false,
                attachments = attachments,
            )
            _state.update { it.copy(messages = it.messages + ariMessage) }

            // Speak text and action confirmations alike — both are just user-facing strings now
            if (response is FfiResponse.Text || response is FfiResponse.Action || response is FfiResponse.NotUnderstood) {
                if (responseText.isNotBlank()) speechOutput.speak(responseText)
            }
        }
    }

    private companion object {
        /** How long processInput can block before we surface a "still working" bubble. */
        const val STILL_WORKING_DELAY_MS: Long = 4000

        /** Phrase shown when the threshold trips. Mirrors Layer C's vocabulary
         * deliberately so the two paths feel like one feature to the user. */
        const val STILL_WORKING_PHRASE: String = "Working…"
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
            text = result.text,
            isFromUser = false,
            attachments = result.attachments,
        )
        _state.update { it.copy(messages = it.messages + message) }
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
        _state.update {
            it.copy(messages = it.messages + userMessage + ariMessage, inputText = "")
        }
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
        _state.update {
            it.copy(messages = it.messages + userMessage + ariMessage, inputText = "")
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
                    _state.update { it.copy(messages = it.messages + ariMessage) }
                    if (outcome.text.isNotBlank()) speechOutput.speak(outcome.text)
                }
            }
        }
    }

    fun syncServiceState() {
        _state.update { it.copy(isListening = WakeWordService.isRunning) }
        refreshOnboarding()
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

    fun checkFsnPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = application.getSystemService(NotificationManager::class.java)
            return nm.canUseFullScreenIntent()
        }
        return true
    }

    fun openFsnSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${application.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            application.startActivity(intent)
        }
    }

    fun dismissFsnPrompt() {
        _state.update { it.copy(needsFsnPermission = false) }
    }

    /**
     * Set the wake word service to a desired state. Idempotent against the
     * actual service state, not the displayed state — so we can't get into a
     * "switch says ON, service is OFF" feedback loop.
     */
    fun setWakeWordEnabled(enabled: Boolean) {
        val intent = Intent(application, WakeWordService::class.java)
        if (enabled) {
            if (WakeWordService.isRunning) return
            if (!checkFsnPermission()) {
                _state.update { it.copy(needsFsnPermission = true) }
            }
            ContextCompat.startForegroundService(application, intent)
        } else {
            if (!WakeWordService.isRunning) return
            application.stopService(intent)
        }
        // Suppress the poll loop briefly while the FGS finishes its lifecycle
        // transition, otherwise the user sees an ON → OFF → ON flicker.
        suppressPollUntil = System.currentTimeMillis() + 2500
        _state.update { it.copy(isListening = enabled) }
    }

}
