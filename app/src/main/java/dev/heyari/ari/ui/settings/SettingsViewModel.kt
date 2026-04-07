package dev.heyari.ari.ui.settings

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
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.stt.ModelDownloadManager
import dev.heyari.ari.stt.ModelDownloadState
import dev.heyari.ari.stt.SpeechRecognizer
import dev.heyari.ari.stt.SttModel
import dev.heyari.ari.stt.SttModelRegistry
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

data class SettingsState(
    val permissions: PermissionStatus = PermissionStatus(false, false, false, false),
    val models: List<ModelStatus> = emptyList(),
    val download: ModelDownloadState = ModelDownloadState.Idle,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val application: Application,
    private val downloadManager: ModelDownloadManager,
    private val speechRecognizer: SpeechRecognizer,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        refreshPermissions()
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
}
