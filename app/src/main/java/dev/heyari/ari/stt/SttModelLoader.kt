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

    /** Resolve + warm the active model; suspends until loaded or timeout. The
     *  eager-load call sites invoke it fire-and-forget. */
    suspend fun ensureLoaded(): Outcome {
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
