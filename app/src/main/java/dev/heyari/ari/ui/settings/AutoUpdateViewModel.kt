package dev.heyari.ari.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.heyari.ari.data.AutoUpdatePreferences
import dev.heyari.ari.di.EngineModule
import dev.heyari.ari.llm.LlmDownloadManager
import dev.heyari.ari.llm.LlmModelRegistry
import dev.heyari.ari.stt.ModelDownloadManager
import dev.heyari.ari.stt.SttModelRegistry
import dev.heyari.ari.models.ApplyEvent
import dev.heyari.ari.models.ModelTarget
import dev.heyari.ari.models.ModelUpdate
import dev.heyari.ari.models.ModelUpdateApplier
import dev.heyari.ari.models.ModelUpdateChecker
import dev.heyari.ari.models.ModelUpdateNotifier
import dev.heyari.ari.models.ModelUpdateWorker
import dev.heyari.ari.router.RouterDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val applier: ModelUpdateApplier,
    private val routerDownloadManager: RouterDownloadManager,
    private val llmDownloadManager: LlmDownloadManager,
    private val sttDownloadManager: ModelDownloadManager,
    private val settingsRepository: dev.heyari.ari.data.SettingsRepository,
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
     * Drives the download → verify → swap → reload sequence for a single
     * pending update by collecting events from [ModelUpdateApplier]. The
     * applier owns the engine teardown/reload + skip-clear; this VM only
     * translates events into UI state (button spinner, success toast,
     * notification refresh).
     */
    fun applyUpdate(update: ModelUpdate) {
        viewModelScope.launch {
            _state.update { it.copy(applyingTargetKey = update.target.key) }
            try {
                applier.apply(update).collect { event ->
                    when (event) {
                        is ApplyEvent.Started, is ApplyEvent.Progress -> { /* button spinner is enough */ }
                        is ApplyEvent.Completed -> {
                            val remaining = _state.value.pendingUpdates
                                .filterNot { it.target.key == update.target.key }
                            _state.update {
                                it.copy(
                                    applyingTargetKey = null,
                                    pendingUpdates = remaining,
                                    toast = application.getString(
                                        dev.heyari.ari.R.string.auto_update_apply_succeeded,
                                        event.displayName,
                                        event.version,
                                    ),
                                )
                            }
                            notifier.showOrUpdate(remaining)
                            refreshInstalledModels()
                        }
                        is ApplyEvent.Failed -> {
                            _state.update {
                                it.copy(
                                    applyingTargetKey = null,
                                    toast = application.getString(
                                        dev.heyari.ari.R.string.auto_update_apply_failed,
                                        event.reason,
                                    ),
                                )
                            }
                        }
                    }
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
