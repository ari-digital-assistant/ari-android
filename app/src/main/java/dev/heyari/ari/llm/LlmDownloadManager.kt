package dev.heyari.ari.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null

    private val _state = MutableStateFlow<LlmDownloadState>(LlmDownloadState.Idle)
    val state: StateFlow<LlmDownloadState> = _state.asStateFlow()

    private val llmRoot: File
        get() = File(context.filesDir, "models/llm").apply { mkdirs() }

    fun modelDir(model: LlmModel): File = File(llmRoot, model.id)

    fun modelFile(model: LlmModel): File = File(modelDir(model), model.fileName)

    fun isDownloaded(model: LlmModel): Boolean = modelFile(model).isFile

    fun delete(model: LlmModel): Boolean = modelDir(model).deleteRecursively()

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        _state.value = LlmDownloadState.Idle
    }

    fun download(model: LlmModel) {
        if (currentJob?.isActive == true) {
            Log.w(TAG, "Download already in progress, ignoring request for ${model.id}")
            return
        }

        currentJob = scope.launch {
            val dir = modelDir(model).apply { mkdirs() }
            val target = File(dir, model.fileName)
            val partFile = File(dir, "${model.fileName}.part")

            try {
                _state.value = LlmDownloadState.Downloading(model.id, 0L, model.totalBytes)

                // Resume partial download if possible.
                partFile.delete()

                val connection = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    requestMethod = "GET"
                }

                try {
                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        throw RuntimeException("HTTP $responseCode for ${model.downloadUrl}")
                    }

                    var bytesSoFar = 0L
                    connection.inputStream.use { input ->
                        java.io.FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var lastEmit = 0L

                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                if (!scope.isActive) throw kotlinx.coroutines.CancellationException("download cancelled")

                                output.write(buffer, 0, read)
                                bytesSoFar += read

                                val now = System.currentTimeMillis()
                                if (now - lastEmit > 100) {
                                    _state.update {
                                        LlmDownloadState.Downloading(model.id, bytesSoFar, model.totalBytes)
                                    }
                                    lastEmit = now
                                }
                            }

                            output.flush()
                            output.fd.sync()
                        }
                    }

                    // Atomic rename.
                    target.delete()
                    if (!partFile.renameTo(target)) {
                        throw RuntimeException("Failed to rename ${partFile.name} to ${target.name}")
                    }
                } finally {
                    connection.disconnect()
                }

                _state.value = LlmDownloadState.Completed(model.id)
                Log.i(TAG, "LLM download completed for ${model.id} (${target.length()} bytes)")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    partFile.delete()
                    Log.i(TAG, "LLM download cancelled for ${model.id}")
                    throw t
                }
                Log.e(TAG, "LLM download failed for ${model.id}: ${t.message}", t)
                val friendly = when (t) {
                    is java.net.UnknownHostException ->
                        "Couldn't reach the model server. Check your internet connection."
                    is java.net.SocketTimeoutException ->
                        "Connection timed out. Try again."
                    is java.io.IOException ->
                        "Network error: ${t.message ?: "connection lost"}"
                    else -> t.message ?: "Unknown error"
                }
                _state.value = LlmDownloadState.Failed(model.id, friendly)
            }
        }
    }

    companion object {
        private const val TAG = "LlmDownloadManager"
        private const val BUFFER_SIZE = 64 * 1024
    }
}
