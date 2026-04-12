package dev.heyari.ari.router

import android.content.Context
import android.util.Log
import dev.heyari.ari.di.EngineModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RouterDownloadState {
    data object Idle : RouterDownloadState
    data class Downloading(val bytesSoFar: Long, val totalBytes: Long) : RouterDownloadState
    data class Failed(val error: String) : RouterDownloadState
    data object Completed : RouterDownloadState
}

@Singleton
class RouterDownloadManager @Inject constructor(
    private val context: Context,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null

    private val _state = MutableStateFlow<RouterDownloadState>(RouterDownloadState.Idle)
    val state: StateFlow<RouterDownloadState> = _state.asStateFlow()

    private val routerDir: File
        get() = File(context.filesDir, "models/router").apply { mkdirs() }

    fun modelFile(): File = File(routerDir, EngineModule.ROUTER_MODEL_FILENAME)

    fun isDownloaded(): Boolean = modelFile().isFile

    fun delete(): Boolean = modelFile().delete()

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        _state.value = RouterDownloadState.Idle
    }

    fun download() {
        if (currentJob?.isActive == true) return

        currentJob = scope.launch {
            _state.value = RouterDownloadState.Downloading(0, EngineModule.ROUTER_MODEL_BYTES)

            try {
                val url = URL(EngineModule.ROUTER_MODEL_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                conn.connect()

                val total = conn.contentLengthLong.takeIf { it > 0 } ?: EngineModule.ROUTER_MODEL_BYTES
                val partFile = File(routerDir, "${EngineModule.ROUTER_MODEL_FILENAME}.part")

                conn.inputStream.use { input ->
                    partFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var downloaded = 0L
                        var lastReport = System.currentTimeMillis()

                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n

                            val now = System.currentTimeMillis()
                            if (now - lastReport > 100) {
                                _state.value = RouterDownloadState.Downloading(downloaded, total)
                                lastReport = now
                            }
                        }
                    }
                }

                if (!isActive) {
                    partFile.delete()
                    _state.value = RouterDownloadState.Idle
                    return@launch
                }

                partFile.renameTo(modelFile())
                _state.value = RouterDownloadState.Completed
                Log.i(TAG, "Router model downloaded: ${modelFile().length()} bytes")
            } catch (e: Exception) {
                val msg = when {
                    e is java.net.SocketTimeoutException -> "Download timed out"
                    e is java.net.UnknownHostException -> "No internet connection"
                    else -> e.message ?: "Unknown error"
                }
                Log.e(TAG, "Router download failed", e)
                _state.value = RouterDownloadState.Failed(msg)
            }
        }
    }

    companion object {
        private const val TAG = "RouterDownloadManager"
    }
}
