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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
        return dir.isDirectory &&
            File(dir, model.encoderFile).isFile &&
            File(dir, model.decoderFile).isFile &&
            File(dir, model.joinerFile).isFile &&
            File(dir, model.tokensFile).isFile
    }

    fun downloadedModels(): List<SttModel> = SttModelRegistry.all.filter { isDownloaded(it) }

    fun delete(model: SttModel): Boolean {
        val dir = modelDir(model)
        return dir.deleteRecursively()
    }

    fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    fun download(model: SttModel) {
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
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).map { infos ->
            mapToState(infos)
        }

    private fun mapToState(infos: List<WorkInfo>): ModelDownloadState {
        val info = infos.firstOrNull() ?: return ModelDownloadState.Idle
        return when (info.state) {
            WorkInfo.State.RUNNING -> {
                val modelId = info.progress.getString(ModelDownloadWorker.KEY_MODEL_ID) ?: ""
                val bytesSoFar = info.progress.getLong(ModelDownloadWorker.KEY_BYTES_SO_FAR, 0L)
                val totalBytes = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                if (modelId.isEmpty() && bytesSoFar == 0L) {
                    // Worker just started, no progress yet
                    ModelDownloadState.Downloading("", 0L, 0L, "")
                } else {
                    ModelDownloadState.Downloading(modelId, bytesSoFar, totalBytes, "")
                }
            }
            WorkInfo.State.ENQUEUED -> {
                // Waiting for network or constraints
                ModelDownloadState.Downloading("", 0L, 0L, "")
            }
            WorkInfo.State.SUCCEEDED -> {
                val modelId = info.outputData.getString(ModelDownloadWorker.KEY_MODEL_ID) ?: ""
                ModelDownloadState.Completed(modelId)
            }
            WorkInfo.State.FAILED -> {
                val modelId = info.outputData.getString(ModelDownloadWorker.KEY_MODEL_ID) ?: ""
                val error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Unknown error"
                ModelDownloadState.Failed(modelId, error)
            }
            WorkInfo.State.CANCELLED -> ModelDownloadState.Idle
            WorkInfo.State.BLOCKED -> ModelDownloadState.Downloading("", 0L, 0L, "")
        }
    }

    companion object {
        private const val TAG = "ModelDownloadManager"
        private const val UNIQUE_WORK_NAME = "stt-model-download"
    }
}
