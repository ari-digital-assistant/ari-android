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
import dev.heyari.ari.stt.SttState
import dev.heyari.ari.tts.SpeechOutput
import dev.heyari.ari.wakeword.WakeWordEvents
import dev.heyari.ari.wakeword.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
    private val wakeWordEvents: WakeWordEvents,
    private val actionHandler: ActionHandler,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private var wasListeningBeforeStt = false

    init {
        // React to active model changes — load into recognizer + refresh onboarding state.
        // Loading is a JNI call that takes seconds, so we hop to IO before invoking it.
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.activeSttModelId.collect { activeId ->
                val model = SttModelRegistry.byId(activeId)
                if (model != null && downloadManager.isDownloaded(model) && speechRecognizer.currentModelId != model.id) {
                    runCatching {
                        speechRecognizer.loadModel(model, downloadManager.modelDir(model))
                    }
                }
                refreshOnboarding()
            }
        }

        viewModelScope.launch {
            speechRecognizer.state.collect { sttState ->
                _state.update { it.copy(sttState = sttState) }
                if (sttState is SttState.Done) {
                    onSttResult(sttState.text)
                }
            }
        }

        viewModelScope.launch {
            wakeWordEvents.events.collect {
                onWakeWordDetected()
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
        _state.update {
            it.copy(needsSetup = !hasMic || !hasModel)
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

    fun toggleWakeWord() {
        val newState = !_state.value.isListening
        _state.update { it.copy(isListening = newState) }

        val intent = Intent(application, WakeWordService::class.java)
        if (newState) {
            if (!checkFsnPermission()) {
                _state.update { it.copy(needsFsnPermission = true) }
            }
            ContextCompat.startForegroundService(application, intent)
        } else {
            application.stopService(intent)
        }
    }

    private fun onWakeWordDetected() {
        _state.update { it.copy(wakeWordDetected = true) }
        startSttFromWakeWord()
    }

    fun clearWakeWordDetected() {
        _state.update { it.copy(wakeWordDetected = false) }
    }

    private fun startSttFromWakeWord() {
        if (!speechRecognizer.isModelLoaded) {
            _state.update {
                it.copy(sttState = SttState.Error("No STT model. Open Settings to download one."))
            }
            return
        }

        wasListeningBeforeStt = WakeWordService.isRunning

        if (WakeWordService.isRunning) {
            application.stopService(Intent(application, WakeWordService::class.java))
            _state.update { it.copy(isListening = false) }
        }

        speechRecognizer.startListening()
    }

    fun stopStt() {
        speechRecognizer.stopListening()
    }

    private fun onSttResult(text: String) {
        speechRecognizer.reset()
        onTextSubmitted(text)
        resumeWakeWord()
    }

    private fun resumeWakeWord() {
        if (wasListeningBeforeStt) {
            val intent = Intent(application, WakeWordService::class.java)
            ContextCompat.startForegroundService(application, intent)
            _state.update { it.copy(isListening = true) }
        }
    }
}
