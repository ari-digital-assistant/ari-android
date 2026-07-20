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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
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
    data class Completed(val locale: String) : RouterDownloadState
}

@Singleton
class RouterDownloadManager @Inject constructor(
    private val context: Context,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Guarded by this instance's monitor. Callers arrive from several
    // dispatchers, and the job/locale pair drifting apart would hand a caller
    // the wrong locale's download. [currentToken] is the ownership token: it is
    // bumped on every start and every cancel, so a job whose token is stale has
    // provably been superseded and must keep its hands off [_state].
    private var currentJob: Job? = null
    private var currentLocale: String? = null
    private var currentToken = 0L

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

    @Synchronized
    fun cancel() {
        currentToken++
        currentJob?.cancel()
        currentJob = null
        currentLocale = null
        _state.value = RouterDownloadState.Idle
    }

    /**
     * Cancel any in-flight download whose locale isn't [keep] and wait for it
     * to stop. Callers about to delete locale directories must use this rather
     * than [cancel]: cancellation is cooperative, and a cancelled job has a
     * narrow window in which it can still rename its `.part` into place —
     * recreating the outgoing locale's directory after the sweep removed it.
     */
    suspend fun cancelAndJoinExcept(keep: String?) {
        val job = synchronized(this) {
            if (currentLocale == keep) return
            currentToken++
            val doomed = currentJob
            currentJob = null
            currentLocale = null
            doomed
        }
        job?.cancelAndJoin()
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

    @Synchronized
    private fun startDownload(locale: String, providedManifest: ModelManifest?): Job {
        // Only join an in-flight download if it's fetching the same locale.
        // A language switch mid-download must abandon the old one, or we'd
        // return a job that installs the outgoing locale's model.
        if (currentLocale == locale) {
            currentJob?.takeIf { it.isActive }?.let { return it }
        }
        currentJob?.cancel()
        val token = ++currentToken
        val job = scope.launch { runDownload(locale, providedManifest, token) }
        currentJob = job
        currentLocale = locale
        return job
    }

    /**
     * Publish only while [token] still owns the download. Cancellation is
     * cooperative but `input.read` is not, so a cancelled job can sit in a
     * blocking socket read for up to the read timeout — by which time its
     * successor is already reporting progress on this same flow. Taking the
     * monitor makes the check-and-write atomic against [cancel] and
     * [startDownload], so a stale job's write can never land afterwards.
     */
    @Synchronized
    private fun publish(token: Long, newState: RouterDownloadState) {
        if (token == currentToken) _state.value = newState
    }

    private suspend fun runDownload(locale: String, providedManifest: ModelManifest?, token: Long) {
        val initialTotal = providedManifest?.totalSizeBytes ?: EngineModule.ROUTER_MODEL_BYTES
        publish(token, RouterDownloadState.Downloading(0, initialTotal))

        try {
            val manifest = providedManifest ?: fetchManifestOrNull(locale)
            if (manifest == null) {
                publish(token, RouterDownloadState.Failed("Router manifest unavailable"))
                return
            }
            val sourceFile = manifest.files.firstOrNull()
            if (sourceFile == null) {
                publish(token, RouterDownloadState.Failed("Router manifest lists no files"))
                return
            }
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
                    while (currentCoroutineContext().isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 100) {
                            publish(token, RouterDownloadState.Downloading(downloaded, total))
                            lastReport = now
                        }
                    }
                }
            }

            // Whoever cancelled us has already published on our behalf — Idle
            // from cancel(), Downloading from the incoming locale's job — so
            // bail quietly rather than racing them for the flow.
            if (!currentCoroutineContext().isActive) {
                partFile.delete()
                return
            }

            val actualSha = InstalledModelMetadata.sha256Hex(partFile)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                partFile.delete()
                Log.e(TAG, "SHA-256 mismatch: expected $expectedSha, got $actualSha")
                publish(token, RouterDownloadState.Failed("Checksum mismatch — file may be corrupted"))
                return
            }

            // Hashing a quarter-gigabyte file takes seconds, so re-check before
            // committing: installing a cancelled locale here would recreate the
            // outgoing locale's directory after reconcile had already swept it,
            // leaving two router models on disk.
            if (!currentCoroutineContext().isActive) {
                partFile.delete()
                return
            }

            modelFile(locale).delete()
            if (!partFile.renameTo(modelFile(locale))) {
                partFile.delete()
                publish(token, RouterDownloadState.Failed("Failed to install downloaded file"))
                return
            }
            InstalledModelMetadata.writeSingle(
                routerDir(locale),
                version = version,
                fileName = RouterModel.fileName(locale),
                sha256 = actualSha,
            )

            publish(token, RouterDownloadState.Completed(locale))
            Log.i(TAG, "Router model installed: locale=$locale version=$version size=${modelFile(locale).length()}")
        } catch (e: Exception) {
            val msg = when (e) {
                is java.net.SocketTimeoutException -> "Download timed out"
                is java.net.UnknownHostException -> "No internet connection"
                else -> e.message ?: "Unknown error"
            }
            Log.e(TAG, "Router download failed", e)
            publish(token, RouterDownloadState.Failed(msg))
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
