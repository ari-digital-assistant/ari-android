package dev.heyari.ari.llm

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.heyari.ari.models.InstalledModelMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LlmDownloadState {
    data object Idle : LlmDownloadState
    /**
     * [waiting] is a download that has run before and is queued to resume:
     * stopped by Android's time limit, a lost network, or a retry backing
     * off. [bytesSoFar] is then what the partial file already holds.
     */
    data class Downloading(
        val modelId: String,
        val bytesSoFar: Long,
        val totalBytes: Long,
        val waiting: Boolean = false,
    ) : LlmDownloadState
    data class Failed(val modelId: String, val error: String) : LlmDownloadState
    data class Completed(val modelId: String) : LlmDownloadState
}

@Singleton
class LlmDownloadManager @Inject constructor(
    private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    private val llmRoot: File
        get() = File(context.filesDir, "models/llm").apply { mkdirs() }

    fun modelDir(model: LlmModel): File = File(llmRoot, model.id)

    /**
     * The file to load for [model]: its own, or else the GGUF an earlier
     * version of Ari downloaded for the tier. Ari can't run that, but it
     * keeps the tier counted as installed, so the update check offers its
     * replacement (ari-android#52). The tier's own file when there is neither.
     */
    fun modelFile(model: LlmModel): File = modelFileIn(modelDir(model), model)

    fun isDownloaded(model: LlmModel): Boolean = modelFile(model).isFile

    /** Whether [model] still has only the GGUF its tier used to have. */
    fun isLegacy(model: LlmModel): Boolean = modelFile(model).name == model.legacyFileName

    fun delete(model: LlmModel): Boolean = modelDir(model).deleteRecursively()

    /**
     * Read the installed sidecar version. Missing/corrupt → `unknown`. A
     * legacy GGUF's sidecar describes the GGUF, so it reads `unknown` too,
     * which the update check takes as out of date.
     */
    fun installedVersion(model: LlmModel): String =
        if (isLegacy(model)) InstalledModelMetadata.UNKNOWN_VERSION
        else InstalledModelMetadata.readVersion(modelDir(model))

    /**
     * Auto-update entry point. Triggers the worker, then suspends until
     * the work reaches a terminal state. Returns the final state so
     * callers can switch on Completed / Failed.
     */
    suspend fun downloadAndAwait(model: LlmModel): LlmDownloadState {
        download(model)
        return state.first { it is LlmDownloadState.Completed || it is LlmDownloadState.Failed }
    }

    // Optimistic latch: set synchronously the instant download() is
    // called, so [state] can report Downloading(thatModelId) before
    // WorkManager has produced any WorkInfo. Without this the settings
    // model rows kept showing their download button through the
    // enqueue/early window and the user could tap it repeatedly. Cleared
    // on cancel and when work reaches a terminal state.
    private val requestedModelId = MutableStateFlow<String?>(null)

    fun cancel() {
        requestedModelId.value = null
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        // A running worker drops its own partial on cancel; one queued to
        // resume isn't running to do it.
        llmRoot.walk().filter { it.name.endsWith(".part") }.forEach { it.delete() }
    }

    private fun partialBytes(model: LlmModel): Long =
        modelDir(model).listFiles { f -> f.name.endsWith(".part") }
            ?.maxOfOrNull { it.length() } ?: 0L

    fun download(model: LlmModel) {
        requestedModelId.value = model.id
        val request = OneTimeWorkRequestBuilder<LlmDownloadWorker>()
            .setInputData(workDataOf(LlmDownloadWorker.KEY_MODEL_ID to model.id))
            // Lets [state] name the model of queued work after a process
            // restart has cleared the requested-id latch.
            .addTag(MODEL_TAG_PREFIX + model.id)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        Log.i(TAG, "Enqueued LLM download for ${model.id}")
    }

    val state: Flow<LlmDownloadState> =
        combine(
            workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME),
            requestedModelId,
        ) { infos, requestedId ->
            mapToState(infos, requestedId)
        }.onEach { st ->
            if (st is LlmDownloadState.Completed || st is LlmDownloadState.Failed) {
                requestedModelId.value = null
            }
        }

    private fun mapToState(infos: List<WorkInfo>, requestedId: String?): LlmDownloadState {
        val info = infos.firstOrNull()
        val modelId = requestedId
            ?: info?.tags?.firstOrNull { it.startsWith(MODEL_TAG_PREFIX) }?.removePrefix(MODEL_TAG_PREFIX)
        // Only queued work needs the partial's size, and progress ticks ten
        // times a second while running, so don't stat the disk for those.
        val queued = info?.state == WorkInfo.State.ENQUEUED || info?.state == WorkInfo.State.BLOCKED
        val model = if (queued) LlmModelRegistry.byId(modelId) else null
        return resolveState(
            workState = info?.state,
            progressModelId = info?.progress?.getString(LlmDownloadWorker.KEY_MODEL_ID) ?: "",
            bytesSoFar = info?.progress?.getLong(LlmDownloadWorker.KEY_BYTES_SO_FAR, 0L) ?: 0L,
            totalBytes = info?.progress?.getLong(LlmDownloadWorker.KEY_TOTAL_BYTES, 0L) ?: 0L,
            outputModelId = info?.outputData?.getString(LlmDownloadWorker.KEY_MODEL_ID) ?: "",
            error = info?.outputData?.getString(LlmDownloadWorker.KEY_ERROR),
            requestedId = modelId,
            runAttemptCount = info?.runAttemptCount ?: 0,
            partialBytes = model?.let { partialBytes(it) } ?: 0L,
            modelTotalBytes = model?.totalBytes ?: 0L,
        )
    }

    companion object {
        private const val TAG = "LlmDownloadManager"

        /** [modelFile] for a tier whose directory is [dir]. See `LlmModelFileTest`. */
        internal fun modelFileIn(dir: File, model: LlmModel): File {
            val own = File(dir, model.fileName)
            val legacy = model.legacyFileName?.let { File(dir, it) }
            return if (!own.isFile && legacy?.isFile == true) legacy else own
        }
        private const val UNIQUE_WORK_NAME = "llm-model-download"
        private const val MODEL_TAG_PREFIX = "llm-model:"

        /**
         * Pure mapping from a WorkManager work state (+ the optimistic
         * requested-id latch) to a [LlmDownloadState]. Side-effect and
         * Android-framework free so it can be unit tested — mirrors
         * `ModelDownloadManager.resolveState`. See `LlmDownloadStateTest`.
         */
        internal fun resolveState(
            workState: WorkInfo.State?,
            progressModelId: String,
            bytesSoFar: Long,
            totalBytes: Long,
            outputModelId: String,
            error: String?,
            requestedId: String?,
            runAttemptCount: Int,
            partialBytes: Long,
            modelTotalBytes: Long,
        ): LlmDownloadState = when (workState) {
            null ->
                if (requestedId != null) LlmDownloadState.Downloading(requestedId, 0L, 0L)
                else LlmDownloadState.Idle
            WorkInfo.State.RUNNING ->
                LlmDownloadState.Downloading(
                    progressModelId.ifEmpty { requestedId.orEmpty() },
                    bytesSoFar, totalBytes,
                )
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                if (runAttemptCount > 0) {
                    LlmDownloadState.Downloading(requestedId.orEmpty(), partialBytes, modelTotalBytes, waiting = true)
                } else {
                    LlmDownloadState.Downloading(requestedId.orEmpty(), 0L, 0L)
                }
            WorkInfo.State.SUCCEEDED -> LlmDownloadState.Completed(outputModelId)
            WorkInfo.State.FAILED -> LlmDownloadState.Failed(outputModelId, error ?: "Unknown error")
            WorkInfo.State.CANCELLED -> LlmDownloadState.Idle
        }
    }
}
