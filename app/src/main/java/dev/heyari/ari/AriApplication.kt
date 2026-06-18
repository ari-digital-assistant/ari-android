package dev.heyari.ari

import android.app.Application
import android.app.LocaleManager
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.LocaleList
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.heyari.ari.data.AutoUpdatePreferences
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.models.ModelUpdateWorker
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
import dev.heyari.ari.di.EngineHolder
import javax.inject.Inject

@HiltAndroidApp
class AriApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var engineHolder: EngineHolder
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var speechRecognizer: SpeechRecognizer
    @Inject lateinit var downloadManager: ModelDownloadManager
    @Inject lateinit var autoUpdatePreferences: AutoUpdatePreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Kick the engine build on a background thread right away so it's
        // ready before the first interaction — WITHOUT blocking onCreate.
        // Building it on the main thread (the old @Inject AriEngine field
        // did exactly that) is what tripped the startup ANR.
        engineHolder.warmUp()
        // Idempotent — KEEP policy means reinstalls don't reset the schedule.
        SkillUpdateWorker.schedule(this)
        scheduleModelUpdateWorker()
        NotificationChannels.ensureAll(this)
        eagerLoadActiveSttModel()
        applyPersistedAppLocale()
    }

    /**
     * Mirror the persisted [SettingsRepository.activeLocale] into
     * Android's per-app locale via [LocaleManager] (API 33+).
     * Without this, picking a language in onboarding only flips the
     * engine's view (via `AriFfiLocaleProvider`'s flow subscription)
     * — Android's resource resolution would still pick chrome strings
     * from the system locale, so an Italian user on an English phone
     * would see English UI even though the assistant replies in
     * Italian.
     *
     * No-op on API 29-32 — per-app locale isn't supported. Those
     * users get system-locale chrome but still get the engine's
     * locale-aware behaviour (assistant replies, skill matching,
     * fallback text) because that path goes through SettingsRepository
     * directly, not Android resource resolution.
     *
     * Per-app locale set via LocaleManager is sticky across reboots —
     * Android persists it itself.
     */
    private fun applyPersistedAppLocale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        scope.launch {
            runCatching {
                val locale = settingsRepository.activeLocale.first()
                val localeManager = getSystemService(LocaleManager::class.java)
                val current = localeManager.applicationLocales
                val currentTag = if (current.isEmpty) "" else current[0].toLanguageTag()
                // No-op when already in sync — setting the same locale
                // triggers an Activity recreate, which would interrupt
                // the user mid-task on every cold start.
                if (currentTag.startsWith(locale)) return@runCatching
                localeManager.applicationLocales = LocaleList.forLanguageTags(locale)
            }.onFailure { Log.w(TAG, "applyPersistedAppLocale failed", it) }
        }
    }

    /**
     * Schedule the model auto-update worker. Reads the user's metered-data
     * preference so the constraint matches their consent. Settings UI calls
     * [ModelUpdateWorker.schedule] with `replace = true` when the toggle is
     * flipped at runtime; this start-up call uses `KEEP` so it doesn't
     * clobber an in-flight reschedule.
     */
    private fun scheduleModelUpdateWorker() {
        scope.launch {
            val allowMetered = autoUpdatePreferences.allowMetered.first()
            val enabled = autoUpdatePreferences.enabled.first()
            if (enabled) {
                ModelUpdateWorker.schedule(this@AriApplication, allowMetered = allowMetered)
            } else {
                ModelUpdateWorker.cancel(this@AriApplication)
            }
        }
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
            // Best-effort: if the engine hasn't been built yet there's no
            // mmap to release, so a null peek is a no-op rather than forcing
            // a (blocking) build on a memory-pressure callback.
            engineHolder.peek()?.let {
                Log.i(TAG, "Memory pressure (level=$level), unloading LLM")
                it.unloadLlmModel()
            }
        }
    }

    private companion object {
        const val TAG = "AriApplication"
    }
}
