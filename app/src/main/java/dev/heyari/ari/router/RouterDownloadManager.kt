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
    private var currentLocale: String? = null

    private val _state = MutableStateFlow<RouterDownloadState>(RouterDownloadState.Idle)
    val state: StateFlow<RouterDownloadState> = _state.asStateFlow()

    /** Parent of every per-locale directory. Migration and cleanup walk this. */
    val routerRootDir: File
        get() = File(context.filesDir, "models/router").apply { mkdirs() }

    fun routerDir(locale: String): File = File(routerRootDir, locale).apply { mkdirs() }

    fun modelFile(locale: String): File = File(routerDir(locale), RouterModel.fileName(locale))

    fun isDownloaded(locale: String): Boolean = modelFile(locale).isFile

    /** Read the installed sidecar version. Missing/corrupt → `unknown`. */
    fun installedVersion(locale: String): String = InstalledModelMetadata.readVersion(routerDir(locale))

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        currentLocale = null
        _state.value = RouterDownloadState.Idle
    }

    /**
     * First-install / refresh path. Fetches the published manifest for
     * [locale], then downloads + verifies + writes the sidecar. If the
     * manifest can't be reached the download fails and is retried on the
     * next reconcile — there is no fallback URL, because the only artifact
     * we could fall back to is English and serving it to another locale is
     * never correct.
     */
    fun download(locale: String) {
        startDownload(locale, providedManifest = null)
    }

    /**
     * Auto-update path. Suspends until the download completes. Caller is
     * expected to inspect [state] afterwards to see whether it landed on
     * [RouterDownloadState.Completed] or [RouterDownloadState.Failed].
     */
    suspend fun downloadWithManifest(locale: String, manifest: ModelManifest) {
        val job = startDownload(locale, providedManifest = manifest)
        job.join()
    }

    private fun startDownload(locale: String, providedManifest: ModelManifest?): Job {
        // Only join an in-flight download if it's fetching the same locale.
        // A language switch mid-download must abandon the old one, or we'd
        // return a job that installs the outgoing locale's model.
        if (currentLocale == locale) {
            currentJob?.takeIf { it.isActive }?.let { return it }
        }
        currentJob?.cancel()
        val job = scope.launch { runDownload(locale, providedManifest) }
        currentJob = job
        currentLocale = locale
        return job
    }

    private suspend fun runDownload(locale: String, providedManifest: ModelManifest?) {
        val initialTotal = providedManifest?.totalSizeBytes ?: EngineModule.ROUTER_MODEL_BYTES
        _state.value = RouterDownloadState.Downloading(0, initialTotal)

        try {
            val manifest = providedManifest ?: fetchManifestOrNull(locale)
            if (manifest == null) {
                _state.value = RouterDownloadState.Failed("Router manifest unavailable")
                return
            }
            val sourceFile = manifest.files.first()
            val expectedSha = sourceFile.sha256
            val version = manifest.version

            val conn = (URL(sourceFile.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                connect()
            }

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: sourceFile.sizeBytes
            val partFile = File(routerDir(locale), "${RouterModel.fileName(locale)}.part")

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
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                partFile.delete()
                Log.e(TAG, "SHA-256 mismatch: expected $expectedSha, got $actualSha")
                _state.value = RouterDownloadState.Failed("Checksum mismatch — file may be corrupted")
                return
            }

            modelFile(locale).delete()
            if (!partFile.renameTo(modelFile(locale))) {
                partFile.delete()
                _state.value = RouterDownloadState.Failed("Failed to install downloaded file")
                return
            }
            InstalledModelMetadata.writeSingle(
                routerDir(locale),
                version = version,
                fileName = RouterModel.fileName(locale),
                sha256 = actualSha,
            )

            _state.value = RouterDownloadState.Completed
            Log.i(TAG, "Router model installed: locale=$locale version=$version size=${modelFile(locale).length()}")
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

    private fun fetchManifestOrNull(locale: String): ModelManifest? = try {
        val url = URL(RouterModel.manifestUrl(locale))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            connect()
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        ModelManifest.parse(text).also {
            Log.i(TAG, "Fetched router manifest for $locale: version=${it.version}")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Router manifest fetch failed for $locale: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "RouterDownloadManager"
    }
}
