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
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.model.ConversationState
import dev.heyari.ari.model.Message
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
    private val settingsRepository: SettingsRepository,
    private val actionHandler: ActionHandler,
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

        val userMessage = Message(text = text, isFromUser = true)
        _state.update { it.copy(messages = it.messages + userMessage, inputText = "", wakeWordDetected = false) }

        viewModelScope.launch(Dispatchers.Default) {
            val response = engine.processInput(text)
            val responseText = when (response) {
                is FfiResponse.Text -> response.body
                is FfiResponse.Action -> actionHandler.handle(response.json)
                is FfiResponse.Binary -> "[Binary: ${response.mime}, ${response.data.size} bytes]"
            }
            val ariMessage = Message(text = responseText, isFromUser = false)
            _state.update { it.copy(messages = it.messages + ariMessage) }

            // Speak text and action confirmations alike — both are just user-facing strings now
            if (response is FfiResponse.Text || response is FfiResponse.Action) {
                speechOutput.speak(responseText)
            }
        }
    }

    fun syncServiceState() {
        _state.update { it.copy(isListening = WakeWordService.isRunning) }
        refreshOnboarding()
    }

    private fun refreshOnboarding() {
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
