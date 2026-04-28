package dev.heyari.ari.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.heyari.ari.data.AutoUpdatePreferences
import dev.heyari.ari.di.EngineModule
import dev.heyari.ari.llm.LlmDownloadManager
import dev.heyari.ari.llm.LlmDownloadState
import dev.heyari.ari.llm.LlmModelRegistry
import dev.heyari.ari.stt.ModelDownloadManager
import dev.heyari.ari.stt.ModelDownloadState
import dev.heyari.ari.stt.SpeechRecognizer
import dev.heyari.ari.stt.SttModelRegistry
import dev.heyari.ari.models.ModelTarget
import dev.heyari.ari.models.ModelUpdate
import dev.heyari.ari.models.ModelUpdateChecker
import dev.heyari.ari.models.ModelUpdateNotifier
import dev.heyari.ari.models.ModelUpdateWorker
import dev.heyari.ari.router.RouterDownloadManager
import dev.heyari.ari.router.RouterDownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.ari_ffi.AriEngine
import java.time.Instant
import javax.inject.Inject

data class InstalledModelRow(
    val target: ModelTarget,
    val installedVersion: String,
)

data class AutoUpdateState(
    val masterEnabled: Boolean = true,
    val allowMetered: Boolean = false,
    val checking: Boolean = false,
    val applyingTargetKey: String? = null,
    val pendingUpdates: List<ModelUpdate> = emptyList(),
    val installedModels: List<InstalledModelRow> = emptyList(),
    val lastCheckedAt: Instant? = null,
    val toast: String? = null,
)

/**
 * Drives the Settings → Auto-update panel. Owns the master toggle, the
 * metered-network toggle, the "check now" affordance, and (in stage 8)
 * the per-update Apply / Skip flow.
 */
@HiltViewModel
class AutoUpdateViewModel @Inject constructor(
    private val application: Application,
    private val preferences: AutoUpdatePreferences,
    private val checker: ModelUpdateChecker,
    private val notifier: ModelUpdateNotifier,
    private val routerDownloadManager: RouterDownloadManager,
    private val llmDownloadManager: LlmDownloadManager,
    private val sttDownloadManager: ModelDownloadManager,
    private val speechRecognizer: SpeechRecognizer,
    private val settingsRepository: dev.heyari.ari.data.SettingsRepository,
    private val engine: AriEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(AutoUpdateState())
    val state: StateFlow<AutoUpdateState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.enabled.collect { enabled ->
                _state.update { it.copy(masterEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.allowMetered.collect { allow ->
                _state.update { it.copy(allowMetered = allow) }
            }
        }
        viewModelScope.launch {
            preferences.lastChecked(AutoUpdatePreferences.CATEGORY_ROUTER).collect { instant ->
                _state.update { it.copy(lastCheckedAt = instant) }
            }
        }
        refreshInstalledModels()
    }

    fun setMasterEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setEnabled(enabled)
            if (enabled) {
                ModelUpdateWorker.schedule(
                    application,
                    allowMetered = preferences.allowMetered.first(),
                    replace = true,
                )
            } else {
                ModelUpdateWorker.cancel(application)
                notifier.cancel()
                _state.update { it.copy(pendingUpdates = emptyList()) }
            }
        }
    }

    fun setAllowMetered(allow: Boolean) {
        viewModelScope.launch {
            preferences.setAllowMetered(allow)
            // Reschedule with the new constraint so the next check honours it.
            if (preferences.enabled.first()) {
                ModelUpdateWorker.schedule(application, allowMetered = allow, replace = true)
            }
        }
    }

    fun checkNow() {
        viewModelScope.launch {
            _state.update { it.copy(checking = true) }
            try {
                val updates = withContext(Dispatchers.IO) { checker.checkForUpdates() }
                preferences.setLastChecked(AutoUpdatePreferences.CATEGORY_ROUTER, Instant.now())
                _state.update { it.copy(pendingUpdates = updates) }
                notifier.showOrUpdate(updates)
                if (updates.isEmpty()) {
                    _state.update { it.copy(toast = application.getString(dev.heyari.ari.R.string.auto_update_no_updates)) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "checkNow failed", e)
                _state.update {
                    it.copy(toast = application.getString(dev.heyari.ari.R.string.auto_update_apply_failed, e.message ?: "unknown"))
                }
            } finally {
                _state.update { it.copy(checking = false) }
            }
        }
    }

    fun skipUpdate(update: ModelUpdate) {
        viewModelScope.launch {
            preferences.setSkippedVersion(update.target.key, update.availableVersion)
            _state.update { current ->
                current.copy(pendingUpdates = current.pendingUpdates.filterNot { it.target.key == update.target.key })
            }
            // If nothing remains pending, drop the notification too.
            if (_state.value.pendingUpdates.isEmpty()) notifier.cancel()
            else notifier.showOrUpdate(_state.value.pendingUpdates)
        }
    }

    fun resetSkippedVersions() {
        viewModelScope.launch { preferences.clearAllSkippedVersions() }
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    /**
     * Orchestrates the download → verify → swap → reload sequence for a
     * single pending update. Each model category has its own engine
     * teardown/reload pattern; the manifest-driven download with SHA
     * verification and atomic .part-then-rename is shared.
     */
    fun applyUpdate(update: ModelUpdate) {
        viewModelScope.launch {
            _state.update { it.copy(applyingTargetKey = update.target.key) }
            try {
                when (val target = update.target) {
                    is ModelTarget.Router -> applyRouterUpdate(update)
                    is ModelTarget.Llm -> applyLlmUpdate(update, target)
                    is ModelTarget.Stt -> applySttUpdate(update, target)
                }
            } catch (e: Exception) {
                Log.e(TAG, "applyUpdate failed for ${update.target.key}", e)
                _state.update {
                    it.copy(
                        applyingTargetKey = null,
                        toast = application.getString(
                            dev.heyari.ari.R.string.auto_update_apply_failed,
                            e.message ?: "unknown",
                        ),
                    )
                }
            }
        }
    }

    private suspend fun applyRouterUpdate(update: ModelUpdate) {
        // 1. Release the engine's mmap on the old GGUF before we overwrite
        //    it on disk. Lazy lifecycle handles the in-flight case: if a
        //    routing inference is mid-call, the existing mutex serialises
        //    the unload until that completes. Routing during the gap
        //    falls through to the legacy keyword scorer — same as if the
        //    user disabled the router.
        withContext(Dispatchers.IO) { engine.unloadRouterModel() }

        // 2. Download to .part, verify SHA-256, atomic rename, write
        //    sidecar. State flow updates progress so the UI button can
        //    show "Updating…" until completion.
        routerDownloadManager.downloadWithManifest(update.manifest)
        when (val finalState = routerDownloadManager.state.value) {
            is RouterDownloadState.Completed -> {} // proceed
            is RouterDownloadState.Failed -> {
                _state.update {
                    it.copy(
                        applyingTargetKey = null,
                        toast = application.getString(
                            dev.heyari.ari.R.string.auto_update_apply_failed,
                            finalState.error,
                        ),
                    )
                }
                // Engine is mid-cycle (unloaded, no new model). Best-effort
                // re-load of whatever was on disk before — if the rename
                // never touched the original, this restores routing.
                if (routerDownloadManager.isDownloaded()) {
                    withContext(Dispatchers.IO) {
                        engine.loadRouterModel(routerDownloadManager.modelFile().absolutePath)
                    }
                }
                return
            }
            else -> {
                _state.update {
                    it.copy(
                        applyingTargetKey = null,
                        toast = application.getString(
                            dev.heyari.ari.R.string.auto_update_apply_failed,
                            "download did not complete",
                        ),
                    )
                }
                return
            }
        }

        // 3. Re-load the engine with the freshly-installed file.
        val ok = withContext(Dispatchers.IO) {
            engine.loadRouterModel(routerDownloadManager.modelFile().absolutePath)
        }
        if (!ok) {
            _state.update {
                it.copy(
                    applyingTargetKey = null,
                    toast = application.getString(
                        dev.heyari.ari.R.string.auto_update_apply_failed,
                        "engine refused new model",
                    ),
                )
            }
            return
        }

        // 4. Settle UI state, refresh sidecar-derived rows, dismiss the
        //    notification if no other updates remain pending.
        val remaining = _state.value.pendingUpdates.filterNot { it.target.key == update.target.key }
        _state.update {
            it.copy(
                applyingTargetKey = null,
                pendingUpdates = remaining,
                toast = application.getString(
                    dev.heyari.ari.R.string.auto_update_apply_succeeded,
                    update.target.displayName,
                    update.availableVersion,
                ),
            )
        }
        // Drop any user-skip for this model — they just installed it, so
        // any future update should re-prompt.
        preferences.setSkippedVersion(update.target.key, null)
        notifier.showOrUpdate(remaining)
        refreshInstalledModels()
    }

    private suspend fun applyLlmUpdate(update: ModelUpdate, target: ModelTarget.Llm) {
        val model = target.model
        // 1. Release the engine's mmap on the old GGUF. The engine's
        //    LazyLlmFallback serialises calls behind a mutex, so any
        //    in-flight QA inference completes before the unload returns.
        //    Subsequent QA calls during the swap fall through to "no LLM
        //    loaded" — same as if the user picked None temporarily.
        withContext(Dispatchers.IO) { engine.unloadLlmModel() }

        // 2. Trigger the WorkManager-backed download and suspend until
        //    the worker reports terminal state. The worker handles
        //    manifest fetch + SHA verify + atomic rename + sidecar write
        //    internally. Failure leaves the existing target untouched
        //    because SHA verification happens before the target.delete().
        val finalState = llmDownloadManager.downloadAndAwait(model)
        when (finalState) {
            is LlmDownloadState.Completed -> {} // proceed
            is LlmDownloadState.Failed -> {
                _state.update {
                    it.copy(
                        applyingTargetKey = null,
                        toast = application.getString(
                            dev.heyari.ari.R.string.auto_update_apply_failed,
                            finalState.error,
                        ),
                    )
                }
                // Best-effort restore of the previous model so QA doesn't
                // silently break for the rest of the session.
                if (llmDownloadManager.isDownloaded(model)) {
                    withContext(Dispatchers.IO) {
                        engine.loadLlmModel(llmDownloadManager.modelFile(model).absolutePath)
                    }
                }
                return
            }
            else -> {
                _state.update {
                    it.copy(
                        applyingTargetKey = null,
                        toast = application.getString(
                            dev.heyari.ari.R.string.auto_update_apply_failed,
                            "download did not complete",
                        ),
                    )
                }
                return
            }
        }

        // 3. Re-load the engine with the new GGUF.
        val ok = withContext(Dispatchers.IO) {
            engine.loadLlmModel(llmDownloadManager.modelFile(model).absolutePath)
        }
        if (!ok) {
            _state.update {
                it.copy(
                    applyingTargetKey = null,
                    toast = application.getString(
                        dev.heyari.ari.R.string.auto_update_apply_failed,
                        "engine refused new model",
                    ),
                )
            }
            return
        }

        // 4. Settle UI state, drop any user-skip, refresh sidecar-derived
        //    rows, dismiss the notification if no other updates remain
        //    pending.
        val remaining = _state.value.pendingUpdates.filterNot { it.target.key == update.target.key }
        _state.update {
            it.copy(
                applyingTargetKey = null,
                pendingUpdates = remaining,
                toast = application.getString(
                    dev.heyari.ari.R.string.auto_update_apply_succeeded,
                    update.target.displayName,
                    update.availableVersion,
                ),
            )
        }
        preferences.setSkippedVersion(update.target.key, null)
        notifier.showOrUpdate(remaining)
        refreshInstalledModels()
    }

    private suspend fun applySttUpdate(update: ModelUpdate, target: ModelTarget.Stt) {
        val model = target.model
        // 1. Release sherpa-onnx file handles. Sherpa mmap's the encoder /
        //    decoder / joiner; we need to drop those before overwriting
        //    the on-disk files cleanly. Wake-word detection itself runs
        //    on a separate (microWakeWord) detector and isn't affected.
        withContext(Dispatchers.IO) { speechRecognizer.unload() }

        // 2. Trigger the bundle download. Worker fetches manifest,
        //    downloads each component file, verifies per-file SHA-256,
        //    atomic-renames each, and writes the bundle sidecar.
        val finalState = sttDownloadManager.downloadAndAwait(model)
        when (finalState) {
            is ModelDownloadState.Completed -> {} // proceed
            is ModelDownloadState.Failed -> {
                _state.update {
                    it.copy(
                        applyingTargetKey = null,
                        toast = application.getString(
                            dev.heyari.ari.R.string.auto_update_apply_failed,
                            finalState.error,
                        ),
                    )
                }
                // Best-effort restore so subsequent voice queries don't
                // silently fail. Existing on-disk files (potentially a
                // mix of old and partial-new) get the recogniser back up.
                if (sttDownloadManager.isDownloaded(model)) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            speechRecognizer.loadModel(model, sttDownloadManager.modelDir(model))
                        }
                    }
                }
                return
            }
            else -> {
                _state.update {
                    it.copy(
                        applyingTargetKey = null,
                        toast = application.getString(
                            dev.heyari.ari.R.string.auto_update_apply_failed,
                            "download did not complete",
                        ),
                    )
                }
                return
            }
        }

        // 3. Reload sherpa-onnx with the new files. This re-runs the
        //    warmup pass (~3s for Nemotron) so the next utterance
        //    after the swap doesn't pay the JIT cost mid-decode.
        val reloadResult = withContext(Dispatchers.IO) {
            runCatching {
                speechRecognizer.loadModel(model, sttDownloadManager.modelDir(model))
            }
        }
        if (reloadResult.isFailure) {
            _state.update {
                it.copy(
                    applyingTargetKey = null,
                    toast = application.getString(
                        dev.heyari.ari.R.string.auto_update_apply_failed,
                        reloadResult.exceptionOrNull()?.message ?: "recogniser refused new model",
                    ),
                )
            }
            return
        }

        // 4. Settle UI state.
        val remaining = _state.value.pendingUpdates.filterNot { it.target.key == update.target.key }
        _state.update {
            it.copy(
                applyingTargetKey = null,
                pendingUpdates = remaining,
                toast = application.getString(
                    dev.heyari.ari.R.string.auto_update_apply_succeeded,
                    update.target.displayName,
                    update.availableVersion,
                ),
            )
        }
        preferences.setSkippedVersion(update.target.key, null)
        notifier.showOrUpdate(remaining)
        refreshInstalledModels()
    }

    private fun refreshInstalledModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val rows = mutableListOf<InstalledModelRow>()
            if (routerDownloadManager.isDownloaded()) {
                rows += InstalledModelRow(
                    target = ModelTarget.Router,
                    installedVersion = routerDownloadManager.installedVersion(),
                )
            }
            val activeLlmId = settingsRepository.activeLlmModelId.first()
            val activeLlm = LlmModelRegistry.byId(activeLlmId)
            if (activeLlm != null && llmDownloadManager.isDownloaded(activeLlm)) {
                rows += InstalledModelRow(
                    target = ModelTarget.Llm(activeLlm),
                    installedVersion = llmDownloadManager.installedVersion(activeLlm),
                )
            }
            val activeSttId = settingsRepository.activeSttModelId.first()
            val activeStt = SttModelRegistry.byId(activeSttId)
            if (activeStt != null && sttDownloadManager.isDownloaded(activeStt)) {
                rows += InstalledModelRow(
                    target = ModelTarget.Stt(activeStt),
                    installedVersion = sttDownloadManager.installedVersion(activeStt),
                )
            }
            _state.update { it.copy(installedModels = rows) }
        }
    }

    companion object {
        private const val TAG = "AutoUpdateViewModel"
        // Suppress unused warning until stage 10 lights up the LLM mirror.
        @Suppress("unused") private const val ROUTER_KEY = EngineModule.ROUTER_MODEL_KEY
    }
}
