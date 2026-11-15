package dev.heyari.ari.llm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.heyari.ari.R
import dev.heyari.ari.models.InstalledModelMetadata
import dev.heyari.ari.models.ModelManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * WorkManager-backed LLM model downloader.
 *
 * Two paths:
 *
 * - **Manifest-driven** (auto-update + new installs): the worker fetches
 *   the manifest URL specified on the [LlmModel], pulls down the GGUF
 *   from `manifest.url`, verifies SHA-256 against `manifest.sha256`,
 *   then writes a `version.json` sidecar.
 *
 * - **Legacy direct** (existing installs predating manifests, or when
 *   the manifest endpoint is unreachable): falls back to
 *   [LlmModel.downloadUrl], skips SHA verification, writes a sidecar
 *   with `version=unknown`. The next auto-update check then offers to
 *   replace it with a properly-versioned copy.
 *
 * SHA verification happens on the `.part` file *before* the existing
 * target is touched, so a corrupt download never destroys an
 * already-installed model.
 *
 * Downloads resume. Background work gets ten minutes before Android stops
 * it, and a lost network stops it too; WorkManager then reruns the worker,
 * which picks the `.part` file up with an HTTP Range request instead of
 * starting again. Until issue #36 every rerun deleted the part first, so a
 * slow multi-GB download looped forever at whatever it reached in ten
 * minutes. The worker also asks to run in the foreground with a progress
 * notification, which lifts the ten-minute cap when Android allows it.
 */
@HiltWorker
class LlmDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID)
            ?: return@withContext Result.failure()
        val model = LlmModelRegistry.byId(modelId)
            ?: return@withContext Result.failure()

        val llmRoot = File(applicationContext.filesDir, "models/llm").apply { mkdirs() }
        val dir = File(llmRoot, model.id).apply { mkdirs() }
        val target = File(dir, model.fileName)
        var partFile: File? = null
        var bytesThisRun = 0L

        try {
            val manifest = if (model.manifestUrl.isNotBlank()) fetchManifest(model.manifestUrl) else null
            val downloadUrl = manifest?.files?.firstOrNull()?.url ?: model.downloadUrl
            val expectedSha = manifest?.files?.firstOrNull()?.sha256
            val expectedTotal = manifest?.files?.firstOrNull()?.sizeBytes ?: model.totalBytes
            val version = manifest?.version ?: InstalledModelMetadata.UNKNOWN_VERSION

            // Named for the bytes it holds, so a half-finished copy of an
            // older upstream build is never resumed into a newer one. Any
            // other .part in the directory is exactly that, and goes.
            val part = File(dir, "${model.fileName}.${expectedSha?.take(12) ?: "unverified"}.part")
            partFile = part
            dir.listFiles { f -> f.name.endsWith(".part") && f.name != part.name }
                ?.forEach { it.delete() }

            val offset = part.length()
            val displayName = applicationContext.getString(model.displayNameRes)
            val foreground = promoteToForeground(displayName, offset, expectedTotal)
            setProgress(workDataOf(
                KEY_BYTES_SO_FAR to offset,
                KEY_TOTAL_BYTES to expectedTotal,
                KEY_MODEL_ID to modelId,
            ))

            // With a manifest the size is exact, so a part of that size is
            // whole (a run stopped between the last byte and the rename).
            val alreadyWhole = expectedSha != null && offset == expectedTotal
            if (!alreadyWhole) {
                val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    requestMethod = "GET"
                    if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
                }

                try {
                    val responseCode = connection.responseCode
                    if (responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
                        // The range starts at or past the end of the file:
                        // the part is whole, unless the server's copy is a
                        // different length to what we hold.
                        val serverSize = connection.getHeaderField("Content-Range")
                            ?.substringAfter('/')?.toLongOrNull()
                        if (serverSize != offset) {
                            part.delete()
                            throw IOException("HTTP 416 for $downloadUrl with $offset bytes held")
                        }
                    } else {
                        if (responseCode !in 200..299) {
                            throw HttpStatusException(responseCode, downloadUrl)
                        }
                        // 200 to a range request means the server ignored the
                        // range and is sending the whole file: start over.
                        val append = responseCode == HttpURLConnection.HTTP_PARTIAL
                        var bytesSoFar = if (append) offset else 0L

                        connection.inputStream.use { input ->
                            FileOutputStream(part, append).use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                var lastEmit = 0L
                                var lastNotify = 0L

                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    if (isStopped) throw CancellationException("download stopped")

                                    output.write(buffer, 0, read)
                                    bytesSoFar += read
                                    bytesThisRun += read

                                    val now = System.currentTimeMillis()
                                    if (now - lastEmit > 100) {
                                        setProgress(workDataOf(
                                            KEY_BYTES_SO_FAR to bytesSoFar,
                                            KEY_TOTAL_BYTES to expectedTotal,
                                            KEY_MODEL_ID to modelId,
                                        ))
                                        lastEmit = now
                                    }
                                    if (foreground && now - lastNotify > 1_000) {
                                        setForeground(foregroundInfo(displayName, bytesSoFar, expectedTotal))
                                        lastNotify = now
                                    }
                                }

                                output.flush()
                                output.fd.sync()
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }

            val actualSha = InstalledModelMetadata.sha256Hex(part)
            if (expectedSha != null && !actualSha.equals(expectedSha, ignoreCase = true)) {
                part.delete()
                throw RuntimeException("Checksum mismatch — file may be corrupted")
            }

            // SHA passed (or wasn't available). Now atomic-replace the target.
            target.delete()
            if (!part.renameTo(target)) {
                throw RuntimeException("Failed to rename ${part.name} to ${target.name}")
            }
            InstalledModelMetadata.writeSingle(
                modelDir = dir,
                version = version,
                fileName = model.fileName,
                sha256 = actualSha,
            )

            Log.i(TAG, "LLM download completed for $modelId v$version (${target.length()} bytes)")
            Result.success(workDataOf(KEY_MODEL_ID to modelId))
        } catch (t: Throwable) {
            when {
                isStopped -> {
                    // Stopped, not failed. A cancel (the user's, or a download
                    // of another model replacing this one) drops the partial.
                    // Anything else, the ten-minute cap or a lost network,
                    // keeps it, and WorkManager reruns us to resume.
                    if (stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) partFile?.delete()
                    Log.i(TAG, "LLM download for $modelId stopped (reason $stopReason) at ${partFile?.length()} bytes")
                    Result.retry()
                }
                isTransient(t) && (bytesThisRun > 0 || runAttemptCount < MAX_ATTEMPTS) -> {
                    // A run that moved the download forward always earns
                    // another; one that got nowhere counts towards the limit.
                    Log.w(TAG, "LLM download for $modelId interrupted, will resume: ${t.message}")
                    Result.retry()
                }
                else -> {
                    // The part stays (unless the checksum condemned it), so
                    // tapping Download again resumes rather than restarts.
                    Log.e(TAG, "LLM download failed for $modelId: ${t.message}", t)
                    Result.failure(workDataOf(
                        KEY_MODEL_ID to modelId,
                        KEY_ERROR to friendlyError(t),
                    ))
                }
            }
        }
    }

    /**
     * Android 12+ refuses a foreground start while the app is in the
     * background, which is where the daily auto-update check runs. The
     * download still completes without the notification: it resumes across
     * WorkManager's ten-minute runs instead of finishing in one.
     */
    private suspend fun promoteToForeground(displayName: String, bytes: Long, total: Long): Boolean = try {
        setForeground(foregroundInfo(displayName, bytes, total))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalStateException) {
        Log.w(TAG, "LLM download running without a notification: ${e.message}")
        false
    }

    private fun foregroundInfo(displayName: String, bytes: Long, total: Long): ForegroundInfo {
        val context = applicationContext
        context.getSystemService<NotificationManager>()?.let { ensureChannel(it) }
        val percent = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ari_symbolic)
            .setContentTitle(context.getString(R.string.notif_model_download_title, displayName))
            .setContentText(context.getString(
                R.string.notif_model_download_progress,
                Formatter.formatShortFileSize(context, bytes),
                Formatter.formatShortFileSize(context, total),
            ))
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.notif_channel_model_downloads_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = applicationContext.getString(R.string.notif_channel_model_downloads_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun fetchManifest(manifestUrl: String): ModelManifest? = try {
        val conn = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            connect()
        }
        if (conn.responseCode !in 200..299) {
            Log.w(TAG, "manifest fetch returned HTTP ${conn.responseCode}: $manifestUrl")
            null
        } else {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            ModelManifest.parse(text).also {
                Log.i(TAG, "Fetched LLM manifest: version=${it.version}")
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "LLM manifest fetch failed, will use legacy URL: ${e.message}")
        null
    }

    private class HttpStatusException(val code: Int, url: String) : RuntimeException("HTTP $code for $url")

    companion object {
        const val TAG = "LlmDownloadWorker"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_BYTES_SO_FAR = "bytes_so_far"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_ERROR = "error"
        private const val BUFFER_SIZE = 64 * 1024
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val MAX_ATTEMPTS = 5
        private const val CHANNEL_ID = "model-downloads"
        private const val NOTIFICATION_ID = 0x5CE5

        private fun isTransient(t: Throwable): Boolean = when (t) {
            is IOException -> true
            is HttpStatusException -> t.code == 429 || t.code >= 500
            else -> false
        }

        private fun friendlyError(t: Throwable): String = when (t) {
            is java.net.UnknownHostException ->
                "Couldn't reach the model server. Check your internet connection."
            is java.net.SocketTimeoutException ->
                "Connection timed out. Try again."
            is java.io.IOException ->
                "Network error: ${t.message ?: "connection lost"}"
            else -> t.message ?: "Unknown error"
        }
    }
}
