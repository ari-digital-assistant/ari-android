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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    fun download(model: LlmModel) {
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
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).map { infos ->
            mapToState(infos)
        }

    private fun mapToState(infos: List<WorkInfo>): LlmDownloadState {
        val info = infos.firstOrNull() ?: return LlmDownloadState.Idle
        return when (info.state) {
            WorkInfo.State.RUNNING -> {
                val modelId = info.progress.getString(LlmDownloadWorker.KEY_MODEL_ID) ?: ""
                val bytesSoFar = info.progress.getLong(LlmDownloadWorker.KEY_BYTES_SO_FAR, 0L)
                val totalBytes = info.progress.getLong(LlmDownloadWorker.KEY_TOTAL_BYTES, 0L)
                LlmDownloadState.Downloading(modelId, bytesSoFar, totalBytes)
            }
            WorkInfo.State.ENQUEUED -> LlmDownloadState.Downloading("", 0L, 0L)
            WorkInfo.State.SUCCEEDED -> {
                val modelId = info.outputData.getString(LlmDownloadWorker.KEY_MODEL_ID) ?: ""
                LlmDownloadState.Completed(modelId)
            }
            WorkInfo.State.FAILED -> {
                val modelId = info.outputData.getString(LlmDownloadWorker.KEY_MODEL_ID) ?: ""
                val error = info.outputData.getString(LlmDownloadWorker.KEY_ERROR) ?: "Unknown error"
                LlmDownloadState.Failed(modelId, error)
            }
            WorkInfo.State.CANCELLED -> LlmDownloadState.Idle
            WorkInfo.State.BLOCKED -> LlmDownloadState.Downloading("", 0L, 0L)
        }
    }

    companion object {
        private const val TAG = "LlmDownloadManager"
        private const val UNIQUE_WORK_NAME = "llm-model-download"
    }
}
