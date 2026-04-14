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

        // Wake word events and STT are handled by the system overlay
        // (VoiceSession + VoiceOverlayManager) — the activity no longer
        // collects them. Keeps the activity focused on typed input + chat
        // history while voice runs entirely from the foreground service.

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

        val userMessage = Message(text = text, isFromUser = true)
        _state.update { it.copy(messages = it.messages + userMessage, inputText = "", wakeWordDetected = false) }

        viewModelScope.launch(Dispatchers.Default) {
            val response = engine.processInput(text)
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
            actions = emptyList(),
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
        when (action.id) {
            "stop_alert" -> {
                // Local intercept — the alert id corresponds to the card's
                // alert primitive; we don't have it directly, so look it up.
                val card = cardRepository.state.value.firstOrNull { it.id == cardId }
                val alertId = card?.onComplete?.alert?.id ?: return
                application.startService(AlertService.stopIntent(application, alertId))
            }
            else -> {
                val utterance = action.utterance ?: return
                if (cardId.startsWith("card_demo-")) {
                    // Demo card lives outside the skill — just drop it.
                    cardRepository.removeById(cardId)
                    cardAlarmScheduler.cancel(cardId)
                    return
                }
                viewModelScope.launch(Dispatchers.Default) {
                    val response = engine.processInput(utterance)
                    if (response is FfiResponse.Action) {
                        actionHandler.handle(response.json, response.skillId)
                    }
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
