package dev.heyari.ari.ui.settings

import android.Manifest
import android.app.Application
import android.app.LocaleManager
import android.util.Log
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.heyari.ari.audio.ClipStats
import dev.heyari.ari.data.SecretStore
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.di.EngineModule
import dev.heyari.ari.llm.LlmDownloadManager
import dev.heyari.ari.llm.LlmDownloadState
import dev.heyari.ari.llm.LlmModel
import dev.heyari.ari.llm.LlmModelRegistry
import dev.heyari.ari.router.RouterDownloadManager
import dev.heyari.ari.router.RouterDownloadState
import dev.heyari.ari.router.RouterPolicy
import dev.heyari.ari.router.loadRouterWithFloor
import dev.heyari.ari.stt.CloudTranscriber
import dev.heyari.ari.stt.ModelDownloadManager
import dev.heyari.ari.stt.ModelDownloadState
import dev.heyari.ari.stt.SpeechRecognizer
import dev.heyari.ari.stt.SttMode
import dev.heyari.ari.stt.SttModel
import dev.heyari.ari.stt.SttModelRegistry
import dev.heyari.ari.stt.UtteranceCaptureStore
import dev.heyari.ari.tts.SpeechOutput
import dev.heyari.ari.wakeword.WakeCaptureStore
import dev.heyari.ari.wakeword.WakeWordModel
import dev.heyari.ari.wakeword.WakeWordRegistry
import dev.heyari.ari.wakeword.WakeWordSensitivity
import dev.heyari.ari.wakeword.WakeWordService
import dev.heyari.ari.di.EngineHolder
import uniffi.ari_ffi.AssistantRegistry
import uniffi.ari_ffi.FfiConfigField
import uniffi.ari_ffi.FfiSettingsQueryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val location: Boolean,
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
    /**
     * ISO 639-1 lowercase language code (e.g. `"en"`, `"it"`) for this voice's
     * locale. Used by the picker to pre-select voices matching the user's
     * active Ari language without parsing [locale]'s display name.
     */
    val localeLanguage: String,
    val active: Boolean,
    val activeIsNetwork: Boolean,
)

data class SettingsState(
    val permissions: PermissionStatus = PermissionStatus(false, false, false, false, false),
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
    val keepFalseTriggerAudio: Boolean = false,
    val wakeCaptureStats: ClipStats = ClipStats(0, 0L),
    val keepUtteranceAudio: Boolean = false,
    val utteranceCaptureStats: ClipStats = ClipStats(0, 0L),
    val keepEverythingAudio: Boolean = false,
    val bargeInEnabled: Boolean = true,
    val conversationMemoryEnabled: Boolean = true,
    val rememberedFacts: List<String> = emptyList(),
    val ttsVoices: List<TtsVoiceOption> = emptyList(),
    val activeTtsVoice: String? = null,
    /** ISO 639-1 lowercase code of the user's active language. */
    val activeLocale: String = "en",
    /** On-device or cloud transcription — the only STT choice the user makes. */
    val sttMode: SttMode = SttMode.ON_DEVICE,
    /** Base URL of the OpenAI-compatible transcription endpoint. */
    val cloudSttEndpoint: String = "",
    /** Model name sent to that endpoint. */
    val cloudSttModel: String = "",
    /** API key for it. Blank is legitimate — a self-hosted endpoint needs none. */
    val cloudSttApiKey: String = "",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val application: Application,
    private val downloadManager: ModelDownloadManager,
    private val llmDownloadManager: LlmDownloadManager,
    private val speechRecognizer: SpeechRecognizer,
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
    private val engineHolder: EngineHolder,
    private val assistantRegistry: AssistantRegistry,
    private val routerDownloadManager: RouterDownloadManager,
    private val routerPolicy: RouterPolicy,
    private val speechOutput: SpeechOutput,
    private val wakeCaptureStore: WakeCaptureStore,
    private val utteranceCaptureStore: UtteranceCaptureStore,
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
                    // Mirror the size into the built-in assistant's model_tier
                    // config so apply_to_engine can construct
                    // ActiveAssistant::Builtin { tier } and Layer C can gate
                    // on it. setAssistantConfig writes through to the FFI
                    // store + DataStore + re-applies the engine.
                    setAssistantConfig(
                        EngineModule.BUILTIN_ASSISTANT_ID,
                        "model_tier",
                        model.size.name.lowercase(),
                    )
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
                        engineHolder.engine().unloadLlmModel()
                        loadedLlmId = null
                    }
                    return@collect
                }
                val model = LlmModelRegistry.byId(llmId)
                if (model == null) {
                    if (loadedLlmId != null) {
                        engineHolder.engine().unloadLlmModel()
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

        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.keepFalseTriggerAudio.collect { enabled ->
                val stats = wakeCaptureStore.stats()
                _state.update {
                    it.copy(
                        keepFalseTriggerAudio = enabled,
                        wakeCaptureStats = stats,
                    )
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.keepEverythingAudio.collect { enabled ->
                _state.update { it.copy(keepEverythingAudio = enabled) }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.keepUtteranceAudio.collect { enabled ->
                val stats = utteranceCaptureStore.stats()
                _state.update {
                    it.copy(
                        keepUtteranceAudio = enabled,
                        utteranceCaptureStats = stats,
                    )
                }
            }
        }

        viewModelScope.launch {
            settingsRepository.bargeInEnabled.collect { enabled ->
                _state.update { it.copy(bargeInEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            settingsRepository.conversationMemoryEnabled.collect { enabled ->
                _state.update { it.copy(conversationMemoryEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            settingsRepository.rememberedFacts.collect { facts ->
                _state.update { it.copy(rememberedFacts = facts) }
            }
        }

        // TTS voice selection
        viewModelScope.launch {
            settingsRepository.activeTtsVoice.collect { activeVoiceName ->
                val voices = speechOutput.getAvailableVoices()

                // Group by variant: strip -local / -network suffix to merge
                // pairs into a single UI entry. `language` is captured
                // alongside `locale` (display name) so the picker can
                // pre-select voices matching the user's active Ari locale
                // without having to round-trip through Locale parsing.
                data class Variant(val key: String, val locale: String, val language: String)

                val groups = mutableMapOf<Variant, MutableMap<Boolean, String>>()
                for (voice in voices) {
                    val raw = voice.name
                    val net = voice.isNetworkConnectionRequired
                    val key = raw.removeSuffix("-local").removeSuffix("-network")
                    val variant = Variant(
                        key = key,
                        locale = voice.locale.displayName,
                        language = voice.locale.language.lowercase(),
                    )
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
                        localeLanguage = variant.language,
                        active = isActive,
                        activeIsNetwork = activeVoiceName == networkName,
                    )
                }
                _state.update { it.copy(ttsVoices = options, activeTtsVoice = activeVoiceName) }
            }
        }

        // Active language — mirror SettingsRepository.activeLocale into UI state
        // so the General settings page can render it and the language picker
        // can read the current selection.
        viewModelScope.launch {
            settingsRepository.activeLocale.collect { code ->
                _state.update { it.copy(activeLocale = code) }
            }
        }

        // STT mode + cloud endpoint config. The API key is read once from the
        // encrypted store rather than observed — it has no flow, and it only
        // changes when this screen writes it.
        viewModelScope.launch {
            settingsRepository.sttMode.collect { mode ->
                _state.update { it.copy(sttMode = mode) }
            }
        }
        viewModelScope.launch {
            settingsRepository.cloudSttEndpoint.collect { url ->
                _state.update { it.copy(cloudSttEndpoint = url) }
            }
        }
        viewModelScope.launch {
            settingsRepository.cloudSttModel.collect { model ->
                _state.update { it.copy(cloudSttModel = model) }
            }
        }
        _state.update {
            it.copy(
                cloudSttApiKey = secretStore.get(
                    CloudTranscriber.SECRET_SCOPE,
                    CloudTranscriber.SECRET_KEY,
                ).orEmpty(),
            )
        }

        // Load the router into the engine once its background download
        // lands, while it's still wanted. The download itself is kicked by
        // [RouterPolicy] whenever the active assistant or locale makes the
        // router necessary — it's no longer a user toggle.
        viewModelScope.launch {
            routerDownloadManager.state.collect { dlState ->
                if (dlState is RouterDownloadState.Completed
                    && settingsRepository.routerEnabled.first()
                    && dlState.locale == settingsRepository.activeLocale.first()
                ) {
                    withContext(Dispatchers.IO) {
                        engineHolder.engine().loadRouterWithFloor(routerDownloadManager, dlState.locale)
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

    fun setKeepFalseTriggerAudio(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setKeepFalseTriggerAudio(enabled)
        }
    }

    fun clearWakeCaptures() {
        viewModelScope.launch(Dispatchers.IO) {
            wakeCaptureStore.clear()
            val stats = wakeCaptureStore.stats()
            _state.update { it.copy(wakeCaptureStats = stats) }
        }
    }

    fun wakeCaptureShareIntent(): Intent? = wakeCaptureStore.shareIntent()

    fun setKeepUtteranceAudio(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setKeepUtteranceAudio(enabled)
        }
    }

    fun setKeepEverythingAudio(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setKeepEverythingAudio(enabled)
        }
    }

    fun clearUtteranceCaptures() {
        viewModelScope.launch(Dispatchers.IO) {
            utteranceCaptureStore.clear()
            val stats = utteranceCaptureStore.stats()
            _state.update { it.copy(utteranceCaptureStats = stats) }
        }
    }

    fun utteranceCaptureShareIntent(): Intent? = utteranceCaptureStore.shareIntent()

    fun setBargeInEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBargeInEnabled(enabled)
        }
    }

    fun setConversationMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setConversationMemoryEnabled(enabled)
            // Push straight through to the live engine so the change takes
            // effect without an app restart. `engine()` suspends until the
            // build completes; a wipe of any existing buffer happens engine-side.
            engineHolder.engine().setConversationMemoryEnabled(enabled)
        }
    }

    /**
     * Delete a single remembered fact from the settings screen. Persists the
     * trimmed list to DataStore AND pushes it into the live engine so the fact
     * is gone from both the durable copy and the in-memory list without an app
     * restart.
     */
    fun forgetFact(fact: String) {
        viewModelScope.launch {
            val updated = settingsRepository.rememberedFactsOnce().filterNot { it == fact }
            settingsRepository.setRememberedFacts(updated)
            engineHolder.engine().setRememberedFacts(updated)
        }
    }

    /** Clear every remembered fact — from DataStore and the live engine. */
    fun forgetAllFacts() {
        viewModelScope.launch {
            settingsRepository.setRememberedFacts(emptyList())
            engineHolder.engine().setRememberedFacts(emptyList())
        }
    }

    /**
     * Onboarding commit point. The chosen language isn't persisted yet at
     * this point in the wizard, so the assistant screen passes the decision
     * in directly: a model published for that language → router required.
     * The assistant choice doesn't enter into it — see [RouterPolicy].
     * Outside onboarding the router is reconciled automatically from
     * persisted state — see [reconcileRouter].
     */
    fun setRouterRequired(required: Boolean) {
        viewModelScope.launch {
            routerPolicy.reconcile(engineHolder.engine(), required)
        }
    }

    private suspend fun reconcileRouter() {
        routerPolicy.reconcileFromState(engineHolder.engine())
    }

    /**
     * Persist the user's chosen language and apply it to the running
     * app. The DataStore flow fans out to the engine (via
     * `AriFfiLocaleProvider`'s subscription for assistant replies + skill
     * matching) and any composables observing `state.activeLocale`, while
     * [applyAppLocale] flips Android's per-app locale so the UI chrome
     * (string resources) re-resolves straight away. Wrapped in runCatching
     * so an unsupported code surfaces as a no-op rather than crashing the
     * settings screen.
     */
    fun setActiveLocale(code: String) {
        viewModelScope.launch {
            runCatching {
                settingsRepository.setActiveLocale(code)
                applyAppLocale(code)
                // Each language has its own router model, and some have none
                // at all, so a language change can flip whether the router is
                // wanted and always changes which file it wants.
                reconcileRouter()
            }.onFailure { Log.w(TAG, "setActiveLocale($code) failed", it) }
        }
    }

    /**
     * Set [code] as Android's per-app locale. This re-resolves string
     * resources and triggers an Activity recreate, which is exactly what
     * we want on a deliberate language change — without it the chrome
     * stays in the old language until the next process start. Per-app
     * locale is API 33+; on older releases the chrome follows the system
     * locale and only the engine's locale switches live, matching
     * `AriApplication.applyPersistedAppLocale`.
     */
    private fun applyAppLocale(code: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        application.getSystemService(LocaleManager::class.java)
            .applicationLocales = LocaleList.forLanguageTags(code)
    }

    /**
     * Switch between on-device and cloud transcription, and make it take effect
     * now rather than at next launch.
     *
     * Picking on-device also resolves and starts the download for the user's
     * language — they chose "on device", not "Zipformer vs Whisper", so
     * selecting the model is our job (see [SttModelRegistry.onDeviceFor]).
     */
    fun setSttMode(mode: SttMode) {
        viewModelScope.launch {
            settingsRepository.setSttMode(mode)
            speechRecognizer.setCloudMode(mode.isCloud)
            if (mode == SttMode.ON_DEVICE) {
                val model = SttModelRegistry.onDeviceFor(settingsRepository.activeLocale.first())
                selectAndDownloadModel(model)
            }
        }
    }

    fun setCloudSttEndpoint(url: String) {
        _state.update { it.copy(cloudSttEndpoint = url) }
        viewModelScope.launch { settingsRepository.setCloudSttEndpoint(url) }
    }

    fun setCloudSttModel(model: String) {
        _state.update { it.copy(cloudSttModel = model) }
        viewModelScope.launch { settingsRepository.setCloudSttModel(model) }
    }

    fun setCloudSttApiKey(key: String) {
        _state.update { it.copy(cloudSttApiKey = key) }
        secretStore.set(
            CloudTranscriber.SECRET_SCOPE,
            CloudTranscriber.SECRET_KEY,
            key.trim().takeIf { it.isNotEmpty() },
        )
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

        val location = ContextCompat.checkSelfPermission(
            application, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val fullScreenIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            application.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else {
            true
        }

        val systemAlertWindow = Settings.canDrawOverlays(application)

        return PermissionStatus(recordAudio, postNotifications, location, fullScreenIntent, systemAlertWindow)
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

    /**
     * What the on-device section shows: the model for the user's language,
     * plus any other model still sitting on disk so they can reclaim the space.
     *
     * Deliberately not the whole registry — the user picks on-device or cloud,
     * and listing every architecture invites them to choose one they can't use
     * (Kroko cannot transcribe Italian).
     */
    private fun buildModelList(activeId: String?): List<ModelStatus> {
        val locale = _state.value.activeLocale
        val forLocale = SttModelRegistry.onDeviceFor(locale)
        val alsoOnDisk = SttModelRegistry.all
            .filter { it != forLocale && downloadManager.isDownloaded(it) }
        return (listOf(forLocale) + alsoOnDisk).map { model ->
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

    /**
     * Onboarding-wizard convenience: pin `model` as the active STT
     * and kick off its download in the background. Bypasses the
     * normal `selectModel` "must be downloaded first" guard so a
     * non-English user can be auto-routed past the STT picker
     * straight to assistant configuration — Whisper-turbo finishes
     * downloading while they fill in the rest of the wizard, and
     * the existing activeSttModelId observer loads it into the
     * recogniser as soon as the download completes.
     */
    fun selectAndDownloadModel(model: SttModel) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setActiveSttModelId(model.id)
        }
        if (!downloadManager.isDownloaded(model)) {
            downloadManager.download(model)
        }
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
                engineHolder.engine().unloadLlmModel()
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
        // Persist the user's choice immediately — even if the file isn't
        // on disk yet. The load/unload observer below picks up the model
        // and feeds it to the engine once isDownloaded becomes true.
        // Without this, an onboarding user whose SettingsViewModel is
        // torn down before the download completes loses the selection.
        viewModelScope.launch {
            settingsRepository.setActiveLlmModelId(model.id)
            // Mirror the size into model_tier on the built-in assistant
            // so apply_to_engine can construct ActiveAssistant::Builtin
            // { tier } and Layer C can gate consultation by tier.
            // setAssistantConfig writes to the FFI store + DataStore +
            // re-applies the engine.
            setAssistantConfig(
                EngineModule.BUILTIN_ASSISTANT_ID,
                "model_tier",
                model.size.name.lowercase(),
            )
        }
    }

    private suspend fun loadLlmIntoEngine(model: LlmModel) {
        val modelFile = llmDownloadManager.modelFile(model)
        if (modelFile.isFile) {
            val ok = engineHolder.engine().loadLlmModel(modelFile.absolutePath)
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
            // Clear the "you chose Cloud during onboarding but haven't
            // installed one yet" nag flag — the user has now picked
            // an assistant, so the conversation-screen hint card has
            // served its purpose. Doesn't matter which kind they
            // picked (cloud or builtin); the flag's job was to
            // remind them to pick *something*.
            if (id != null) {
                settingsRepository.setPendingCloudAssistantSetup(false)
            }
            viewModelScope.launch(Dispatchers.IO) {
                assistantRegistry.applyToEngine(engineHolder.engine())
            }
            // On-device / none need FunctionGemma; cloud doesn't. Switching
            // to cloud unloads + deletes the 253 MB model; switching back
            // re-downloads it.
            reconcileRouter()
        }
    }

    /**
     * Re-pull every assistant's config schema + current values from the
     * shared in-memory store. Cheap call (just a HashMap walk per
     * field), but necessary because the assistant list is otherwise
     * only refreshed when [SettingsRepository.activeAssistantId]
     * changes — and a setting written from the per-skill detail page
     * doesn't trip that flow, so this screen can otherwise read stale
     * values when revisited after such a write.
     */
    fun refreshActiveAssistantEntries() {
        viewModelScope.launch {
            val activeId = settingsRepository.activeAssistantId.first()
            refreshAssistantEntries(activeId)
        }
    }

    fun setAssistantConfig(skillId: String, key: String, value: String, secret: Boolean = false) {
        viewModelScope.launch {
            assistantRegistry.setAssistantConfigValue(skillId, key, value)
            if (secret) {
                secretStore.set(skillId, key, value)
                // Remove from plain DataStore if it was previously stored there
                settingsRepository.setAssistantConfigValue(skillId, key, null)
            } else {
                settingsRepository.setAssistantConfigValue(skillId, key, value)
            }
            // Refresh config fields to show the updated value.
            val activeId = settingsRepository.activeAssistantId.first()
            refreshAssistantEntries(activeId)
            // Re-apply to engine in case the config change affects routing.
            viewModelScope.launch(Dispatchers.IO) {
                assistantRegistry.applyToEngine(engineHolder.engine())
            }
        }
    }

    /**
     * Settings-time skill invocation for `dynamic_select` config fields —
     * fetches the option list from the skill once its `depends_on`
     * siblings are filled. Mirrors [SkillsViewModel.querySkillSetting]; the
     * Assistants page renders the same [SkillSettingsPanel] but goes through
     * this VM, so the panel needs the query plumbed here too.
     *
     * Bridges to [Dispatchers.IO] and returns the raw FFI result; the
     * composable wraps the call in `runCatching` and renders the
     * `error`/`options` it carries.
     */
    suspend fun querySkillSetting(
        skillId: String,
        field: String,
        values: Map<String, String>,
    ): FfiSettingsQueryResult = withContext(Dispatchers.IO) {
        engineHolder.engine().querySkillSetting(skillId, field, values)
    }

    suspend fun settingsAction(
        skillId: String,
        action: String,
        values: Map<String, String>,
    ): FfiSettingsQueryResult = withContext(Dispatchers.IO) {
        engineHolder.engine().settingsAction(skillId, action, values)
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
