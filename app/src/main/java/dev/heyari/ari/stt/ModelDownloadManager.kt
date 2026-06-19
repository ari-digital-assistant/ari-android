package dev.heyari.ari.stt

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

sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Downloading(val modelId: String, val bytesSoFar: Long, val totalBytes: Long, val currentFile: String) : ModelDownloadState
    data class Failed(val modelId: String, val error: String) : ModelDownloadState
    data class Completed(val modelId: String) : ModelDownloadState
}

@Singleton
class ModelDownloadManager @Inject constructor(
    private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    private val modelsRoot: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun modelDir(model: SttModel): File = File(modelsRoot, model.id)

    fun isDownloaded(model: SttModel): Boolean {
        val dir = modelDir(model)
        if (!dir.isDirectory) return false
        if (!File(dir, model.encoderFile).isFile) return false
        if (!File(dir, model.decoderFile).isFile) return false
        if (!File(dir, model.tokensFile).isFile) return false
        // Whisper and other encoder-decoder models have no joiner.
        val joiner = model.joinerFile ?: return true
        return File(dir, joiner).isFile
    }

    fun downloadedModels(): List<SttModel> = SttModelRegistry.all.filter { isDownloaded(it) }

    fun delete(model: SttModel): Boolean {
        val dir = modelDir(model)
        return dir.deleteRecursively()
    }

    /** Read the installed sidecar version. Missing/corrupt → `unknown`. */
    fun installedVersion(model: SttModel): String =
        InstalledModelMetadata.readVersion(modelDir(model))

    /**
     * Auto-update entry point. Triggers the worker, then suspends until
     * the work reaches a terminal state. Returns the final state so
     * callers can switch on Completed / Failed.
     */
    suspend fun downloadAndAwait(model: SttModel): ModelDownloadState {
        download(model)
        return state.first { it is ModelDownloadState.Completed || it is ModelDownloadState.Failed }
    }

    // Optimistic latch: set synchronously the instant download() is
    // called, so [state] can report Downloading(thatModelId) before
    // WorkManager has produced any WorkInfo. Without this, the onboarding
    // STT row kept showing its download button through the enqueue/early
    // window and the user could tap it repeatedly. Cleared on cancel and
    // when work reaches a terminal state.
    private val requestedModelId = MutableStateFlow<String?>(null)

    fun cancel() {
        requestedModelId.value = null
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    fun download(model: SttModel) {
        requestedModelId.value = model.id
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to model.id))
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
        Log.i(TAG, "Enqueued STT download for ${model.id}")
    }

    val state: Flow<ModelDownloadState> =
        combine(
            workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME),
            requestedModelId,
        ) { infos, requestedId ->
            mapToState(infos, requestedId)
        }.onEach { st ->
            // Drop the optimistic latch once the worker is terminal, so a
            // later empty WorkInfo list resolves to Idle instead of
            // re-synthesising a phantom Downloading from a stale request.
            if (st is ModelDownloadState.Completed || st is ModelDownloadState.Failed) {
                requestedModelId.value = null
            }
        }

    private fun mapToState(infos: List<WorkInfo>, requestedId: String?): ModelDownloadState {
        val info = infos.firstOrNull()
        return resolveState(
            workState = info?.state,
            progressModelId = info?.progress?.getString(ModelDownloadWorker.KEY_MODEL_ID) ?: "",
            bytesSoFar = info?.progress?.getLong(ModelDownloadWorker.KEY_BYTES_SO_FAR, 0L) ?: 0L,
            totalBytes = info?.progress?.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L) ?: 0L,
            outputModelId = info?.outputData?.getString(ModelDownloadWorker.KEY_MODEL_ID) ?: "",
            error = info?.outputData?.getString(ModelDownloadWorker.KEY_ERROR),
            requestedId = requestedId,
        )
    }

    companion object {
        private const val TAG = "ModelDownloadManager"
        private const val UNIQUE_WORK_NAME = "stt-model-download"

        /**
         * Pure mapping from a WorkManager work state (+ the optimistic
         * requested-id latch) to a [ModelDownloadState]. Kept side-effect
         * free and Android-framework free so it can be unit tested — see
         * `ModelDownloadStateTest`.
         *
         * `workState == null` means WorkManager has no WorkInfo yet: if a
         * download was just requested we still report Downloading(thatId)
         * so the UI flips off the download button immediately. During
         * ENQUEUED / early RUNNING the worker hasn't published its own
         * model id into progress yet, so we fall back to the requested id
         * rather than emitting an empty id that matches no row.
         */
        internal fun resolveState(
            workState: WorkInfo.State?,
            progressModelId: String,
            bytesSoFar: Long,
            totalBytes: Long,
            outputModelId: String,
            error: String?,
            requestedId: String?,
        ): ModelDownloadState = when (workState) {
            null ->
                if (requestedId != null) ModelDownloadState.Downloading(requestedId, 0L, 0L, "")
                else ModelDownloadState.Idle
            WorkInfo.State.RUNNING ->
                ModelDownloadState.Downloading(
                    progressModelId.ifEmpty { requestedId.orEmpty() },
                    bytesSoFar, totalBytes, "",
                )
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                ModelDownloadState.Downloading(requestedId.orEmpty(), 0L, 0L, "")
            WorkInfo.State.SUCCEEDED -> ModelDownloadState.Completed(outputModelId)
            WorkInfo.State.FAILED -> ModelDownloadState.Failed(outputModelId, error ?: "Unknown error")
            WorkInfo.State.CANCELLED -> ModelDownloadState.Idle
        }
    }
}
