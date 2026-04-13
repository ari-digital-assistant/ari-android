package dev.heyari.ari.ui.settings

import android.Manifest
import android.app.Application
import android.util.Log
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
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.di.EngineModule
import dev.heyari.ari.llm.LlmDownloadManager
import dev.heyari.ari.llm.LlmDownloadState
import dev.heyari.ari.llm.LlmModel
import dev.heyari.ari.llm.LlmModelRegistry
import dev.heyari.ari.router.RouterDownloadManager
import dev.heyari.ari.router.RouterDownloadState
import dev.heyari.ari.stt.ModelDownloadManager
import dev.heyari.ari.stt.ModelDownloadState
import dev.heyari.ari.stt.SpeechRecognizer
import dev.heyari.ari.stt.SttModel
import dev.heyari.ari.stt.SttModelRegistry
import dev.heyari.ari.tts.SpeechOutput
import dev.heyari.ari.wakeword.WakeWordModel
import dev.heyari.ari.wakeword.WakeWordRegistry
import dev.heyari.ari.wakeword.WakeWordSensitivity
import dev.heyari.ari.wakeword.WakeWordService
import uniffi.ari_ffi.AriEngine
import uniffi.ari_ffi.AssistantRegistry
import uniffi.ari_ffi.FfiConfigField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PermissionStatus(
    val recordAudio: Boolean,
    val postNotifications: Boolean,
    val fullScreenIntent: Boolean,
    val systemAlertWindow: Boolean,
)

data class ModelStatus(
    val model: SttModel,
    val downloaded: Boolean,
    val active: Boolean,
)

data class WakeWordOption(
    val model: WakeWordModel,
    val active: Boolean,
)

data class LlmModelStatus(
    val model: LlmModel,
    val downloaded: Boolean,
    val active: Boolean,
)

data class AssistantUiEntry(
    val id: String,
    val name: String,
    val description: String,
    val provider: String,
    val privacy: String,
    val configFields: List<FfiConfigField>,
)

data class TtsVoiceOption(
    val localName: String?,
    val networkName: String?,
    val displayLabel: String,
    val locale: String,
    val active: Boolean,
    val activeIsNetwork: Boolean,
)

data class SettingsState(
    val permissions: PermissionStatus = PermissionStatus(false, false, false, false),
    val models: List<ModelStatus> = emptyList(),
    val download: ModelDownloadState = ModelDownloadState.Idle,
    val wakeWords: List<WakeWordOption> = emptyList(),
    val wakeWordSensitivity: WakeWordSensitivity = WakeWordSensitivity.DEFAULT,
    val llmModels: List<LlmModelStatus> = emptyList(),
    val llmDownload: LlmDownloadState = LlmDownloadState.Idle,
    val llmNoneActive: Boolean = true,
    val activeAssistantId: String? = null,
    val assistantEntries: List<AssistantUiEntry> = emptyList(),
    val startOnBoot: Boolean = false,
    val routerEnabled: Boolean = false,
    val routerDownloaded: Boolean = false,
    val routerDownloadState: RouterDownloadState = RouterDownloadState.Idle,
    val ttsVoices: List<TtsVoiceOption> = emptyList(),
    val activeTtsVoice: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val application: Application,
    private val downloadManager: ModelDownloadManager,
    private val llmDownloadManager: LlmDownloadManager,
    private val speechRecognizer: SpeechRecognizer,
    private val settingsRepository: SettingsRepository,
    private val engine: AriEngine,
    private val assistantRegistry: AssistantRegistry,
    private val routerDownloadManager: RouterDownloadManager,
    private val speechOutput: SpeechOutput,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** Tracks which LLM model id is currently loaded in the engine, to avoid redundant loads. */
    @Volatile
    private var loadedLlmId: String? = null

    init {
        refreshPermissions()
        viewModelScope.launch {
            settingsRepository.activeWakeWordId.collect { activeId ->
                val resolved = WakeWordRegistry.byId(activeId).id
                _state.update { current ->
                    current.copy(
                        wakeWords = WakeWordRegistry.all.map { WakeWordOption(it, it.id == resolved) }
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.wakeWordSensitivity.collect { name ->
                _state.update { it.copy(wakeWordSensitivity = WakeWordSensitivity.fromName(name)) }
            }
        }
        viewModelScope.launch {
            combine(
                downloadManager.state,
                settingsRepository.activeSttModelId,
            ) { dlState, activeId ->
                Triple(buildModelList(activeId), dlState, activeId)
            }.collect { (models, dlState, activeId) ->
                _state.update { it.copy(models = models, download = dlState) }

                if (dlState is ModelDownloadState.Completed) {
                    val model = SttModelRegistry.byId(dlState.modelId) ?: return@collect
                    // Auto-select the just-downloaded model if no model is currently active
                    if (activeId == null) {
                        settingsRepository.setActiveSttModelId(model.id)
                    } else if (activeId == model.id) {
                        // Already the active model — ensure it's loaded into the recognizer
                        loadModelIfActive(model)
                    }
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.activeSttModelId.collect { activeId ->
                val model = SttModelRegistry.byId(activeId) ?: return@collect
                if (downloadManager.isDownloaded(model) && speechRecognizer.currentModelId != model.id) {
                    runCatching {
                        speechRecognizer.loadModel(model, downloadManager.modelDir(model))
                    }
                    // Force a UI refresh after loading so the radio button updates
                    _state.update { it.copy(models = buildModelList(activeId)) }
                }
            }
        }

        // LLM download state — track download progress for the assistant
        // settings page. When the built-in assistant is active and a model
        // finishes downloading, load it into the engine.
        viewModelScope.launch {
            combine(
                llmDownloadManager.state,
                settingsRepository.activeLlmModelId,
                settingsRepository.activeAssistantId,
            ) { dlState, llmId, assistantId ->
                Triple(dlState, llmId, assistantId)
            }.collect { (dlState, llmId, assistantId) ->
                _state.update {
                    it.copy(
                        llmModels = buildLlmModelList(llmId),
                        llmDownload = dlState,
                    )
                }

                // Auto-select a just-downloaded model for the built-in assistant.
                if (dlState is LlmDownloadState.Completed
                    && assistantId == EngineModule.BUILTIN_ASSISTANT_ID
                    && llmId == null
                ) {
                    val model = LlmModelRegistry.byId(dlState.modelId) ?: return@collect
                    settingsRepository.setActiveLlmModelId(model.id)
                }
            }
        }

        // Load/unload the LLM into the engine when the active model changes
        // and the built-in assistant is active.
        viewModelScope.launch(Dispatchers.IO) {
            combine(
                settingsRepository.activeLlmModelId,
                settingsRepository.activeAssistantId,
            ) { llmId, assistantId -> Pair(llmId, assistantId) }
            .collect { (llmId, assistantId) ->
                if (assistantId != EngineModule.BUILTIN_ASSISTANT_ID) {
                    if (loadedLlmId != null) {
                        engine.unloadLlmModel()
                        loadedLlmId = null
                    }
                    return@collect
                }
                val model = LlmModelRegistry.byId(llmId)
                if (model == null) {
                    if (loadedLlmId != null) {
                        engine.unloadLlmModel()
                        loadedLlmId = null
                    }
                    return@collect
                }
                if (model.id != loadedLlmId && llmDownloadManager.isDownloaded(model)) {
                    loadLlmIntoEngine(model)
                }
            }
        }

        // Assistant UI state — load entries from registry and track active selection.
        viewModelScope.launch {
            settingsRepository.activeAssistantId.collect { activeId ->
                refreshAssistantEntries(activeId)
            }
        }

        viewModelScope.launch {
            settingsRepository.startOnBoot.collect { enabled ->
                _state.update { it.copy(startOnBoot = enabled) }
            }
        }

        // TTS voice selection
        viewModelScope.launch {
            settingsRepository.activeTtsVoice.collect { activeVoiceName ->
                val voices = speechOutput.getAvailableVoices()

                // Group by variant: strip -local / -network suffix to merge
                // pairs into a single UI entry.
                data class Variant(val key: String, val locale: String)

                val groups = mutableMapOf<Variant, MutableMap<Boolean, String>>()
                for (voice in voices) {
                    val raw = voice.name
                    val net = voice.isNetworkConnectionRequired
                    val key = raw.removeSuffix("-local").removeSuffix("-network")
                    val variant = Variant(key, voice.locale.displayName)
                    groups.getOrPut(variant) { mutableMapOf() }[net] = raw
                }

                val sorted = groups.entries.sortedWith(compareBy({ it.key.locale }, { it.key.key }))
                val counters = mutableMapOf<String, Int>()
                val options = sorted.map { (variant, variants) ->
                    val n = counters.merge(variant.locale, 1) { a, _ -> a + 1 }
                    val localName = variants[false]
                    val networkName = variants[true]
                    val isActive = localName == activeVoiceName || networkName == activeVoiceName
                    TtsVoiceOption(
                        localName = localName,
                        networkName = networkName,
                        displayLabel = "Voice $n",
                        locale = variant.locale,
                        active = isActive,
                        activeIsNetwork = activeVoiceName == networkName,
                    )
                }
                _state.update { it.copy(ttsVoices = options, activeTtsVoice = activeVoiceName) }
            }
        }

        // Router state
        viewModelScope.launch {
            combine(
                settingsRepository.routerEnabled,
                routerDownloadManager.state,
            ) { enabled, dlState -> Pair(enabled, dlState) }
            .collect { (enabled, dlState) ->
                _state.update {
                    it.copy(
                        routerEnabled = enabled,
                        routerDownloaded = routerDownloadManager.isDownloaded(),
                        routerDownloadState = dlState,
                    )
                }
                // Auto-load the router when download completes and it's enabled
                if (dlState is RouterDownloadState.Completed && enabled) {
                    viewModelScope.launch(Dispatchers.IO) {
                        engine.loadRouterModel(routerDownloadManager.modelFile().absolutePath)
                    }
                }
            }
        }
    }

    fun setStartOnBoot(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setStartOnBoot(enabled)
        }
    }

    fun setRouterEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setRouterEnabled(enabled)
            if (enabled) {
                if (routerDownloadManager.isDownloaded()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        engine.loadRouterModel(routerDownloadManager.modelFile().absolutePath)
                    }
                } else {
                    routerDownloadManager.download()
                }
            } else {
                viewModelScope.launch(Dispatchers.IO) {
                    engine.unloadRouterModel()
                }
            }
        }
    }

    fun refreshPermissions() {
        _state.update { it.copy(permissions = readPermissions()) }
    }

    private fun readPermissions(): PermissionStatus {
        val recordAudio = ContextCompat.checkSelfPermission(
            application, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val postNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                application, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val fullScreenIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            application.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else {
            true
        }

        val systemAlertWindow = Settings.canDrawOverlays(application)

        return PermissionStatus(recordAudio, postNotifications, fullScreenIntent, systemAlertWindow)
    }

    /**
     * Opens the system "Display over other apps" page for our package. Holding
     * SYSTEM_ALERT_WINDOW grants Background Activity Launch privilege, which
     * is what lets WakeWordService open the voice overlay over the lock screen
     * on every detection (not just the first one in the FGS BAL grace window).
     * Ari does not actually draw any overlay windows.
     */
    fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${application.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { application.startActivity(intent) }
    }

    private fun buildModelList(activeId: String?): List<ModelStatus> {
        return SttModelRegistry.all.map { model ->
            ModelStatus(
                model = model,
                downloaded = downloadManager.isDownloaded(model),
                active = model.id == activeId,
            )
        }
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

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${application.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        application.startActivity(intent)
    }

    fun openDefaultAssistantSettings() {
        // ROLE_ASSISTANT is held by Google on most devices and createRequestRoleIntent
        // typically returns null, so we deep-link into the system Settings page where
        // the user can manually pick Ari.
        val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { application.startActivity(intent) }.onFailure {
            // Fall back to general settings if voice input page is unavailable
            application.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    fun downloadModel(model: SttModel) {
        downloadManager.download(model)
    }

    fun cancelDownload() {
        downloadManager.cancel()
    }

    fun deleteModel(model: SttModel) {
        if (speechRecognizer.currentModelId == model.id) {
            speechRecognizer.unload()
        }
        downloadManager.delete(model)
        viewModelScope.launch {
            val activeId = settingsRepository.activeSttModelId.first()
            if (activeId == model.id) {
                settingsRepository.setActiveSttModelId(null)
            }
            _state.update { it.copy(models = buildModelList(settingsRepository.activeSttModelId.first())) }
        }
    }

    /**
     * Persist the new wake word and bounce WakeWordService if it's currently
     * running so it picks up the new model. The service holds its detector +
     * AudioRecord across its whole lifetime, so a process-internal restart is
     * the simplest way to swap models without inventing a hot-reload path.
     */
    fun selectWakeWord(model: WakeWordModel) {
        viewModelScope.launch {
            settingsRepository.setActiveWakeWordId(model.id)
            bounceWakeWordService()
        }
    }

    /**
     * Persist the chosen sensitivity and bounce the wake word service so the
     * new cutoff/window take effect immediately. Same restart pattern used for
     * swapping the active wake word model — cheaper than inventing a hot-reload
     * path for a setting users will only change occasionally.
     */
    fun selectWakeWordSensitivity(sensitivity: WakeWordSensitivity) {
        viewModelScope.launch {
            settingsRepository.setWakeWordSensitivity(sensitivity.name)
            bounceWakeWordService()
        }
    }

    private fun bounceWakeWordService() {
        if (WakeWordService.isRunning) {
            val intent = Intent(application, WakeWordService::class.java)
            application.stopService(intent)
            ContextCompat.startForegroundService(application, intent)
        }
    }

    fun selectModel(model: SttModel) {
        if (!downloadManager.isDownloaded(model)) return
        viewModelScope.launch {
            settingsRepository.setActiveSttModelId(model.id)
            loadModelIfActive(model)
        }
    }

    private fun loadModelIfActive(model: SttModel) {
        viewModelScope.launch(Dispatchers.IO) {
            val activeId = settingsRepository.activeSttModelId.first()
            if (activeId == model.id && downloadManager.isDownloaded(model) && speechRecognizer.currentModelId != model.id) {
                runCatching {
                    speechRecognizer.loadModel(model, downloadManager.modelDir(model))
                }
                _state.update { it.copy(models = buildModelList(activeId)) }
            }
        }
    }

    // ── LLM model management (used by built-in assistant) ─────────────

    private fun buildLlmModelList(activeId: String?): List<LlmModelStatus> {
        return LlmModelRegistry.all.map { model ->
            LlmModelStatus(
                model = model,
                downloaded = llmDownloadManager.isDownloaded(model),
                active = model.id == activeId,
            )
        }
    }

    fun downloadLlmModel(model: LlmModel) {
        llmDownloadManager.download(model)
    }

    fun cancelLlmDownload() {
        llmDownloadManager.cancel()
    }

    fun deleteLlmModel(model: LlmModel) {
        viewModelScope.launch {
            val activeId = settingsRepository.activeLlmModelId.first()
            if (activeId == model.id) {
                settingsRepository.setActiveLlmModelId(null)
                engine.unloadLlmModel()
                loadedLlmId = null
            }
            llmDownloadManager.delete(model)
            _state.update { it.copy(llmModels = buildLlmModelList(settingsRepository.activeLlmModelId.first())) }
        }
    }

    /**
     * Select an LLM model tier for the built-in assistant. Also persists
     * the choice as `activeLlmModelId` so the engine can load it.
     */
    fun selectLlmModel(model: LlmModel) {
        if (!llmDownloadManager.isDownloaded(model)) return
        viewModelScope.launch {
            settingsRepository.setActiveLlmModelId(model.id)
        }
    }

    private fun loadLlmIntoEngine(model: LlmModel) {
        val modelFile = llmDownloadManager.modelFile(model)
        if (modelFile.isFile) {
            val ok = engine.loadLlmModel(modelFile.absolutePath)
            if (ok) {
                loadedLlmId = model.id
                Log.i(TAG, "LLM loaded: ${model.id}")
            } else {
                Log.e(TAG, "LLM load failed: ${model.id}")
            }
        }
    }

    // ── Assistant management ───────────────────────────────────────────

    private fun refreshAssistantEntries(activeId: String?) {
        val entries = assistantRegistry.listAssistants().map { ffi ->
            AssistantUiEntry(
                id = ffi.id,
                name = ffi.name,
                description = ffi.description,
                provider = ffi.provider,
                privacy = ffi.privacy,
                configFields = assistantRegistry.getAssistantConfig(ffi.id),
            )
        }
        _state.update {
            it.copy(
                activeAssistantId = activeId,
                assistantEntries = entries,
            )
        }
    }

    fun selectAssistant(id: String?) {
        viewModelScope.launch {
            settingsRepository.setActiveAssistantId(id)
            assistantRegistry.setActiveAssistant(id)
            viewModelScope.launch(Dispatchers.IO) {
                assistantRegistry.applyToEngine(engine)
            }
        }
    }

    fun setAssistantConfig(skillId: String, key: String, value: String) {
        viewModelScope.launch {
            assistantRegistry.setAssistantConfigValue(skillId, key, value)
            settingsRepository.setAssistantConfigValue(skillId, key, value)
            // Refresh config fields to show the updated value.
            val activeId = settingsRepository.activeAssistantId.first()
            refreshAssistantEntries(activeId)
            // Re-apply to engine in case the config change affects routing.
            viewModelScope.launch(Dispatchers.IO) {
                assistantRegistry.applyToEngine(engine)
            }
        }
    }

    // ── TTS voice management ────────────────────────────────────────────

    fun selectTtsVoice(voiceName: String?) {
        viewModelScope.launch {
            settingsRepository.setActiveTtsVoice(voiceName)
            speechOutput.setVoice(voiceName)
        }
    }

    fun previewTtsVoice(voiceName: String) {
        speechOutput.preview(voiceName)
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}
