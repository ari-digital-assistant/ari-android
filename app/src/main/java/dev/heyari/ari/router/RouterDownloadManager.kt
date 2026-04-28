package dev.heyari.ari.router

import android.content.Context
import android.util.Log
import dev.heyari.ari.di.EngineModule
import dev.heyari.ari.models.InstalledModelMetadata
import dev.heyari.ari.models.ModelManifest
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

    val routerDir: File
        get() = File(context.filesDir, "models/router").apply { mkdirs() }

    fun modelFile(): File = File(routerDir, EngineModule.ROUTER_MODEL_FILENAME)

    fun isDownloaded(): Boolean = modelFile().isFile

    /** Read the installed sidecar version. Missing/corrupt → `unknown`. */
    fun installedVersion(): String = InstalledModelMetadata.readVersion(routerDir)

    fun delete(): Boolean {
        File(routerDir, InstalledModelMetadata.SIDECAR_FILENAME).delete()
        return modelFile().delete()
    }

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        _state.value = RouterDownloadState.Idle
    }

    /**
     * First-install / refresh path. Fetches the published manifest, then
     * downloads + verifies + writes the sidecar. If the manifest can't be
     * reached (CDN hiccup, typo'd URL), falls back to the hard-coded
     * legacy URL with SHA verification skipped and a `version=unknown`
     * sidecar — the next auto-update check will offer to repair this
     * once the manifest is back online.
     */
    fun download() {
        startDownload(providedManifest = null)
    }

    /**
     * Auto-update path. Suspends until the download completes. Caller is
     * expected to inspect [state] afterwards to see whether it landed on
     * [RouterDownloadState.Completed] or [RouterDownloadState.Failed].
     */
    suspend fun downloadWithManifest(manifest: ModelManifest) {
        val job = startDownload(providedManifest = manifest)
        job.join()
    }

    private fun startDownload(providedManifest: ModelManifest?): Job {
        currentJob?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch { runDownload(providedManifest) }
        currentJob = job
        return job
    }

    private suspend fun runDownload(providedManifest: ModelManifest?) {
        val initialTotal = providedManifest?.totalSizeBytes ?: EngineModule.ROUTER_MODEL_BYTES
        _state.value = RouterDownloadState.Downloading(0, initialTotal)

        try {
            val manifest = providedManifest ?: fetchManifestOrNull()
            val sourceFile = manifest?.files?.firstOrNull()
            val url = sourceFile?.url ?: EngineModule.ROUTER_MODEL_URL
            val expectedTotal = sourceFile?.sizeBytes ?: EngineModule.ROUTER_MODEL_BYTES
            val expectedSha = sourceFile?.sha256
            val version = manifest?.version ?: InstalledModelMetadata.UNKNOWN_VERSION

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                connect()
            }

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedTotal
            val partFile = File(routerDir, "${EngineModule.ROUTER_MODEL_FILENAME}.part")

            conn.inputStream.use { input ->
                partFile.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var downloaded = 0L
                    var lastReport = System.currentTimeMillis()
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
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

            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                partFile.delete()
                _state.value = RouterDownloadState.Idle
                return
            }

            val actualSha = InstalledModelMetadata.sha256Hex(partFile)
            if (expectedSha != null && !actualSha.equals(expectedSha, ignoreCase = true)) {
                partFile.delete()
                Log.e(TAG, "SHA-256 mismatch: expected $expectedSha, got $actualSha")
                _state.value = RouterDownloadState.Failed("Checksum mismatch — file may be corrupted")
                return
            }

            modelFile().delete()
            if (!partFile.renameTo(modelFile())) {
                partFile.delete()
                _state.value = RouterDownloadState.Failed("Failed to install downloaded file")
                return
            }
            InstalledModelMetadata.writeSingle(
                routerDir,
                version = version,
                fileName = EngineModule.ROUTER_MODEL_FILENAME,
                sha256 = actualSha,
            )

            _state.value = RouterDownloadState.Completed
            Log.i(TAG, "Router model installed: version=$version size=${modelFile().length()}")
        } catch (e: Exception) {
            val msg = when (e) {
                is java.net.SocketTimeoutException -> "Download timed out"
                is java.net.UnknownHostException -> "No internet connection"
                else -> e.message ?: "Unknown error"
            }
            Log.e(TAG, "Router download failed", e)
            _state.value = RouterDownloadState.Failed(msg)
        }
    }

    private fun fetchManifestOrNull(): ModelManifest? = try {
        val url = URL(EngineModule.ROUTER_MODEL_MANIFEST_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            connect()
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        ModelManifest.parse(text).also {
            Log.i(TAG, "Fetched router manifest: version=${it.version}")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Router manifest fetch failed, will use legacy URL: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "RouterDownloadManager"
    }
}
