package dev.heyari.ari.stt

import android.util.Log
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the user's active STT model and loads it on demand. Wraps the
 * "resolve active id -> registry -> isDownloaded -> loadModel" sequence that
 * was duplicated across AriApplication, ConversationViewModel and VoiceSession.
 *
 * [SpeechRecognizer.loadModel] is synchronous, idempotent and internally
 * synchronized, so concurrent callers (e.g. the eager startup load and a
 * wake-triggered VoiceSession) ride the same lock rather than reloading.
 */
@Singleton
class SttModelLoader @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val downloadManager: ModelDownloadManager,
    private val speechRecognizer: SpeechRecognizer,
) {
    enum class Readiness { READY, COLD, NOT_INSTALLED }

    sealed interface Outcome {
        data object Loaded : Outcome
        data object NotInstalled : Outcome
        data object Failed : Outcome
    }

    // Own scope so a load can outlive a timed-out caller: VoiceSession stops
    // waiting after LOAD_TIMEOUT_MS, but the warm-up keeps running here and
    // the next wake finds the model ready.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Resolve the active STT model iff it is downloaded; null otherwise. */
    suspend fun activeDownloadedModel(): SttModel? {
        val model = SttModelRegistry.byId(settingsRepository.activeSttModelId.first()) ?: return null
        return if (downloadManager.isDownloaded(model)) model else null
    }

    /**
     * Move an install off a model this build no longer ships, and reclaim its
     * files. Idempotent and safe to call on every start.
     *
     * Without this a retired id leaves [SttModelRegistry.byId] returning null,
     * which reads downstream as "no model installed" — the user gets no
     * recogniser at all and no explanation, while the retired model's files
     * (663 MB for Nemotron) stay on disk unreferenced.
     *
     * The replacement is chosen by locale, not by asking: see
     * [SttModelRegistry.onDeviceFor]. It is NOT downloaded here — that is the
     * download manager's job and needs the user's network consent — so the
     * next launch reports "not installed" and the UI offers the download,
     * which is the honest outcome rather than a silent 1 GB fetch.
     */
    suspend fun migrateRetiredModel() {
        // Reclaim unconditionally, and before looking at the active id. A user
        // who already switched away from the retired model by hand still has
        // its files, and nothing in the registry can name them any more — so
        // gating the cleanup on "is it still active" leaks the whole 663 MB in
        // the most likely case.
        for (id in SttModelRegistry.retiredIds) {
            if (downloadManager.deleteById(id)) {
                Log.i(TAG, "reclaimed files for retired STT model $id")
            }
        }

        val activeId = settingsRepository.activeSttModelId.first() ?: return
        if (activeId !in SttModelRegistry.retiredIds) return

        val replacement = SttModelRegistry.onDeviceFor(settingsRepository.activeLocale.first())
        settingsRepository.setActiveSttModelId(replacement.id)
        Log.i(TAG, "repointed active STT model $activeId -> ${replacement.id}")
    }

    /**
     * Load [model] (idempotent), bounded by [LOAD_TIMEOUT_MS] so a wedged
     * native load can't hang the caller. Returns true only once the model is
     * actually warm. On timeout the load continues on [scope] in the
     * background.
     */
    suspend fun load(model: SttModel): Boolean {
        val job = scope.launch {
            runCatching { speechRecognizer.loadModel(model, downloadManager.modelDir(model)) }
                .onFailure { Log.w(TAG, "STT loadModel failed for ${model.id}", it) }
        }
        withTimeoutOrNull(LOAD_TIMEOUT_MS) { job.join() }
        return speechRecognizer.isModelLoaded && speechRecognizer.currentModelId == model.id
    }

    /** Resolve + warm the active model; suspends until loaded or timeout, then
     *  reports the [Outcome]. Eager-load call sites await it but ignore the
     *  result; the model-change watcher uses it as a readiness check. */
    suspend fun ensureLoaded(): Outcome {
        // Mode first. Cloud needs nothing downloaded, and applying it here
        // rather than at each call site means a mode change taking effect is
        // the same code path as a model change — one place to be wrong.
        val cloud = settingsRepository.sttMode.first() == SttMode.CLOUD
        speechRecognizer.setCloudMode(cloud)
        if (cloud) return Outcome.Loaded

        val model = activeDownloadedModel()
        return when (decide(speechRecognizer.isModelLoaded, model != null)) {
            Readiness.READY -> Outcome.Loaded
            Readiness.NOT_INSTALLED -> Outcome.NotInstalled
            Readiness.COLD -> if (load(model!!)) Outcome.Loaded else Outcome.Failed
        }
    }

    companion object {
        private const val TAG = "SttModelLoader"
        private const val LOAD_TIMEOUT_MS = 12_000L

        /**
         * Pure readiness decision — side-effect free so it can be unit tested.
         * See [SttModelLoaderTest].
         */
        fun decide(isModelLoaded: Boolean, hasDownloadedModel: Boolean): Readiness = when {
            isModelLoaded -> Readiness.READY
            hasDownloadedModel -> Readiness.COLD
            else -> Readiness.NOT_INSTALLED
        }
    }
}
