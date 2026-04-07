package dev.heyari.ari.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null

    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

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
        currentJob?.cancel()
        currentJob = null
        _state.value = ModelDownloadState.Idle
    }

    fun download(model: SttModel) {
        if (currentJob?.isActive == true) {
            Log.w(TAG, "Download already in progress, ignoring request for ${model.id}")
            return
        }

        currentJob = scope.launch {
            val dir = modelDir(model).apply { mkdirs() }
            val files = listOf(model.encoderFile, model.decoderFile, model.joinerFile, model.tokensFile)

            try {
                _state.value = ModelDownloadState.Downloading(model.id, 0L, model.totalBytes, files.first())

                var bytesSoFar = 0L
                for (file in files) {
                    val target = File(dir, file)
                    if (target.isFile && target.length() > 0L) {
                        bytesSoFar += target.length()
                        _state.value = ModelDownloadState.Downloading(model.id, bytesSoFar, model.totalBytes, file)
                        continue
                    }

                    val url = "${model.baseUrl}/$file"
                    Log.i(TAG, "Downloading $url -> ${target.absolutePath}")

                    val downloaded = downloadFile(url, target, model.id, bytesSoFar, model.totalBytes, file)
                    if (!isActive) {
                        target.delete()
                        return@launch
                    }
                    bytesSoFar += downloaded
                }

                _state.value = ModelDownloadState.Completed(model.id)
                Log.i(TAG, "Download completed for ${model.id}")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    Log.i(TAG, "Download cancelled for ${model.id}")
                    throw t
                }
                Log.e(TAG, "Download failed for ${model.id}: ${t.message}", t)
                val friendly = when (t) {
                    is java.net.UnknownHostException ->
                        "Couldn't reach the model server. Check your internet connection, and make sure Ari has Network permission in app settings."
                    is java.net.SocketTimeoutException ->
                        "Connection timed out. Check your internet connection and try again."
                    is java.io.IOException ->
                        "Network error: ${t.message ?: "connection lost"}"
                    else -> t.message ?: "Unknown error"
                }
                _state.value = ModelDownloadState.Failed(model.id, friendly)
            }
        }
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        modelId: String,
        baseBytesSoFar: Long,
        totalBytes: Long,
        currentFileName: String,
    ): Long {
        val partFile = File(target.parentFile, "${target.name}.part")
        partFile.delete()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            requestMethod = "GET"
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw RuntimeException("HTTP $responseCode for $url")
            }

            var fileBytes = 0L
            connection.inputStream.use { input ->
                java.io.FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastEmit = 0L

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (!scope.isActive) throw kotlinx.coroutines.CancellationException("download cancelled")

                        output.write(buffer, 0, read)
                        fileBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 100) {
                            _state.update {
                                ModelDownloadState.Downloading(
                                    modelId = modelId,
                                    bytesSoFar = baseBytesSoFar + fileBytes,
                                    totalBytes = totalBytes,
                                    currentFile = currentFileName,
                                )
                            }
                            lastEmit = now
                        }
                    }

                    output.flush()
                    output.fd.sync()
                }
            }

            // Streams now closed and fsync'd. Rename atomically.
            target.delete()
            if (!partFile.renameTo(target)) {
                throw RuntimeException("Failed to rename ${partFile.name} to ${target.name}")
            }
            return fileBytes
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "ModelDownloadManager"
        private const val BUFFER_SIZE = 64 * 1024
    }
}
