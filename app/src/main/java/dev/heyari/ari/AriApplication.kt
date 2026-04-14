package dev.heyari.ari

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.notifications.NotificationChannels
import dev.heyari.ari.skills.SkillUpdateWorker
import dev.heyari.ari.stt.ModelDownloadManager
import dev.heyari.ari.stt.SpeechRecognizer
import dev.heyari.ari.stt.SttModelRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uniffi.ari_ffi.AriEngine
import javax.inject.Inject

@HiltAndroidApp
class AriApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var engine: AriEngine
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var speechRecognizer: SpeechRecognizer
    @Inject lateinit var downloadManager: ModelDownloadManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Idempotent — KEEP policy means reinstalls don't reset the schedule.
        SkillUpdateWorker.schedule(this)
        NotificationChannels.ensureAll(this)
        eagerLoadActiveSttModel()
    }

    /**
     * Start loading the user's active STT model as early as possible.
     *
     * Nemotron (663 MB) takes ~3 s to warm sherpa-onnx's recogniser. If the
     * user says "Hey Ari" during that window, VoiceSession sees
     * `isModelLoaded == false` and renders "No speech model installed".
     * Kicking the load here (rather than waiting for ConversationViewModel
     * to be instantiated) means the wake-word-triggered voice path has a
     * head start on every process respawn — fresh install, cold boot, or
     * recovery after OOM-kill. Idempotent: `currentModelId != model.id`
     * short-circuits if a later caller tries to load the same model.
     */
    private fun eagerLoadActiveSttModel() {
        scope.launch {
            runCatching {
                val activeId = settingsRepository.activeSttModelId.first()
                val model = SttModelRegistry.byId(activeId) ?: return@runCatching
                if (!downloadManager.isDownloaded(model)) return@runCatching
                if (speechRecognizer.currentModelId == model.id) return@runCatching
                speechRecognizer.loadModel(model, downloadManager.modelDir(model))
            }.onFailure { Log.w(TAG, "eager STT model load failed", it) }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Log.i(TAG, "Memory pressure (level=$level), unloading LLM")
            engine.unloadLlmModel()
        }
    }

    private companion object {
        const val TAG = "AriApplication"
    }
}
