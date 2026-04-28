package dev.heyari.ari.llm

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.heyari.ari.models.InstalledModelMetadata
import dev.heyari.ari.models.ModelManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
        val partFile = File(dir, "${model.fileName}.part")

        try {
            partFile.delete()

            val manifest = if (model.manifestUrl.isNotBlank()) fetchManifest(model.manifestUrl) else null
            val downloadUrl = manifest?.files?.firstOrNull()?.url ?: model.downloadUrl
            val expectedSha = manifest?.files?.firstOrNull()?.sha256
            val expectedTotal = manifest?.files?.firstOrNull()?.sizeBytes ?: model.totalBytes
            val version = manifest?.version ?: InstalledModelMetadata.UNKNOWN_VERSION

            val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                requestMethod = "GET"
            }

            try {
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw RuntimeException("HTTP $responseCode for $downloadUrl")
                }

                var bytesSoFar = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var lastEmit = 0L

                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            if (isStopped) throw kotlinx.coroutines.CancellationException("download cancelled")

                            output.write(buffer, 0, read)
                            bytesSoFar += read

                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 100) {
                                setProgress(workDataOf(
                                    KEY_BYTES_SO_FAR to bytesSoFar,
                                    KEY_TOTAL_BYTES to expectedTotal,
                                    KEY_MODEL_ID to modelId,
                                ))
                                lastEmit = now
                            }
                        }

                        output.flush()
                        output.fd.sync()
                    }
                }

                val actualSha = InstalledModelMetadata.sha256Hex(partFile)
                if (expectedSha != null && !actualSha.equals(expectedSha, ignoreCase = true)) {
                    partFile.delete()
                    throw RuntimeException("Checksum mismatch — file may be corrupted")
                }

                // SHA passed (or wasn't available). Now atomic-replace the target.
                target.delete()
                if (!partFile.renameTo(target)) {
                    throw RuntimeException("Failed to rename ${partFile.name} to ${target.name}")
                }
                InstalledModelMetadata.writeSingle(
                    modelDir = dir,
                    version = version,
                    fileName = model.fileName,
                    sha256 = actualSha,
                )
            } finally {
                connection.disconnect()
            }

            Log.i(TAG, "LLM download completed for $modelId v$version (${target.length()} bytes)")
            Result.success(workDataOf(KEY_MODEL_ID to modelId))
        } catch (t: Throwable) {
            partFile.delete()
            Log.e(TAG, "LLM download failed for $modelId: ${t.message}", t)
            Result.failure(workDataOf(
                KEY_MODEL_ID to modelId,
                KEY_ERROR to friendlyError(t),
            ))
        }
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

    companion object {
        const val TAG = "LlmDownloadWorker"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_BYTES_SO_FAR = "bytes_so_far"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_ERROR = "error"
        private const val BUFFER_SIZE = 64 * 1024

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
