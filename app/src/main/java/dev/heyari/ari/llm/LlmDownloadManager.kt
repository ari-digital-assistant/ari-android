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
    data class Downloading(val modelId: String, val bytesSoFar: Long, val totalBytes: Long) : LlmDownloadState
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

    fun modelFile(model: LlmModel): File = File(modelDir(model), model.fileName)

    fun isDownloaded(model: LlmModel): Boolean = modelFile(model).isFile

    fun delete(model: LlmModel): Boolean = modelDir(model).deleteRecursively()

    /** Read the installed sidecar version. Missing/corrupt → `unknown`. */
    fun installedVersion(model: LlmModel): String =
        InstalledModelMetadata.readVersion(modelDir(model))

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
    }

    fun download(model: LlmModel) {
        requestedModelId.value = model.id
        val request = OneTimeWorkRequestBuilder<LlmDownloadWorker>()
            .setInputData(workDataOf(LlmDownloadWorker.KEY_MODEL_ID to model.id))
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
        return resolveState(
            workState = info?.state,
            progressModelId = info?.progress?.getString(LlmDownloadWorker.KEY_MODEL_ID) ?: "",
            bytesSoFar = info?.progress?.getLong(LlmDownloadWorker.KEY_BYTES_SO_FAR, 0L) ?: 0L,
            totalBytes = info?.progress?.getLong(LlmDownloadWorker.KEY_TOTAL_BYTES, 0L) ?: 0L,
            outputModelId = info?.outputData?.getString(LlmDownloadWorker.KEY_MODEL_ID) ?: "",
            error = info?.outputData?.getString(LlmDownloadWorker.KEY_ERROR),
            requestedId = requestedId,
        )
    }

    companion object {
        private const val TAG = "LlmDownloadManager"
        private const val UNIQUE_WORK_NAME = "llm-model-download"

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
                LlmDownloadState.Downloading(requestedId.orEmpty(), 0L, 0L)
            WorkInfo.State.SUCCEEDED -> LlmDownloadState.Completed(outputModelId)
            WorkInfo.State.FAILED -> LlmDownloadState.Failed(outputModelId, error ?: "Unknown error")
            WorkInfo.State.CANCELLED -> LlmDownloadState.Idle
        }
    }
}
