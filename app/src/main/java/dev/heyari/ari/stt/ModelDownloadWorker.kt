package dev.heyari.ari.stt

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.heyari.ari.models.InstalledFile
import dev.heyari.ari.models.InstalledModelMetadata
import dev.heyari.ari.models.ModelManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * WorkManager-backed STT bundle downloader. Survives process death —
 * WorkManager persists the enqueued work in its own SQLite store and
 * re-schedules it after the process restarts.
 *
 * Two paths, mirroring [dev.heyari.ari.llm.LlmDownloadWorker]:
 *
 * - **Manifest-driven**: fetches the bundle manifest, downloads each
 *   component file from `manifest.files[].url`, verifies SHA-256
 *   per-file before atomic-renaming over the existing target. Writes a
 *   single bundle sidecar listing per-file shas at the end.
 *
 * - **Legacy direct**: derives URLs from [SttModel.baseUrl], skips
 *   SHA verification, writes a sidecar with `version=unknown`.
 *
 * Per-file SHA verification ensures partial-download corruption never
 * leaves a previously-installed file in a wedged state — the existing
 * target is only deleted once the new file has been verified clean.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID)
            ?: return@withContext Result.failure()
        val model = SttModelRegistry.byId(modelId)
            ?: return@withContext Result.failure()

        val modelsRoot = File(applicationContext.filesDir, "models").apply { mkdirs() }
        val dir = File(modelsRoot, model.id).apply { mkdirs() }

        val manifest = if (model.manifestUrl.isNotBlank()) fetchManifest(model.manifestUrl) else null
        val version = manifest?.version ?: InstalledModelMetadata.UNKNOWN_VERSION

        // Resolve each expected file: prefer manifest entry, fall back to
        // the per-file convention derived from baseUrl. File names are
        // stable per-model so we can match by name. Whisper has no joiner
        // — `joinerFile` is null for encoder-decoder models — so the
        // download list is filtered to skip null entries.
        val plannedFiles = listOfNotNull(
            model.encoderFile, model.decoderFile, model.joinerFile, model.tokensFile,
        ).map { fileName ->
            val manifestEntry = manifest?.files?.firstOrNull { it.name == fileName }
            PlannedFile(
                name = fileName,
                url = manifestEntry?.url ?: "${model.baseUrl}/$fileName",
                expectedSha = manifestEntry?.sha256,
                expectedSize = manifestEntry?.sizeBytes,
            )
        }

        val totalBytes = manifest?.totalSizeBytes ?: model.totalBytes

        try {
            val installedFiles = mutableListOf<InstalledFile>()
            var bytesSoFar = 0L

            for (planned in plannedFiles) {
                val target = File(dir, planned.name)
                // Skip-if-already-present optimisation, but only when we
                // can verify the existing file matches the expected sha.
                // Without a manifest, we trust file-existence as before.
                if (target.isFile && target.length() > 0L) {
                    val matchesExisting = planned.expectedSha?.let { expected ->
                        InstalledModelMetadata.sha256Hex(target).equals(expected, ignoreCase = true)
                    } ?: true
                    if (matchesExisting) {
                        bytesSoFar += target.length()
                        installedFiles += InstalledFile(
                            name = planned.name,
                            sha256 = planned.expectedSha
                                ?: InstalledModelMetadata.sha256Hex(target),
                        )
                        setProgress(workDataOf(
                            KEY_BYTES_SO_FAR to bytesSoFar,
                            KEY_TOTAL_BYTES to totalBytes,
                            KEY_MODEL_ID to modelId,
                        ))
                        continue
                    }
                }

                val (fileBytes, actualSha) = downloadFile(
                    url = planned.url,
                    target = target,
                    expectedSha = planned.expectedSha,
                    modelId = modelId,
                    baseBytesSoFar = bytesSoFar,
                    totalBytes = totalBytes,
                )
                if (isStopped) return@withContext Result.failure()
                bytesSoFar += fileBytes
                installedFiles += InstalledFile(name = planned.name, sha256 = actualSha)
            }

            InstalledModelMetadata.writeBundle(modelDir = dir, version = version, files = installedFiles)

            setProgress(workDataOf(
                KEY_BYTES_SO_FAR to totalBytes,
                KEY_TOTAL_BYTES to totalBytes,
                KEY_MODEL_ID to modelId,
            ))
            Log.i(TAG, "STT bundle downloaded: $modelId v$version")
            Result.success(workDataOf(KEY_MODEL_ID to modelId))
        } catch (t: Throwable) {
            Log.e(TAG, "Download failed for $modelId: ${t.message}", t)
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
                Log.i(TAG, "Fetched STT manifest: version=${it.version}")
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "STT manifest fetch failed, will use legacy URLs: ${e.message}")
        null
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        expectedSha: String?,
        modelId: String,
        baseBytesSoFar: Long,
        totalBytes: Long,
    ): Pair<Long, String> {
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
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastEmit = 0L

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (isStopped) throw kotlinx.coroutines.CancellationException("download cancelled")

                        output.write(buffer, 0, read)
                        fileBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 100) {
                            setProgress(workDataOf(
                                KEY_BYTES_SO_FAR to (baseBytesSoFar + fileBytes),
                                KEY_TOTAL_BYTES to totalBytes,
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
                throw RuntimeException("Checksum mismatch for ${target.name}")
            }

            target.delete()
            if (!partFile.renameTo(target)) {
                throw RuntimeException("Failed to rename ${partFile.name} to ${target.name}")
            }
            return fileBytes to actualSha
        } finally {
            connection.disconnect()
        }
    }

    private data class PlannedFile(
        val name: String,
        val url: String,
        val expectedSha: String?,
        val expectedSize: Long?,
    )

    companion object {
        const val TAG = "ModelDownloadWorker"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_BYTES_SO_FAR = "bytes_so_far"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_ERROR = "error"
        private const val BUFFER_SIZE = 64 * 1024

        private fun friendlyError(t: Throwable): String = when (t) {
            is java.net.UnknownHostException ->
                "Couldn't reach the model server. Check your internet connection, and make sure Ari has Network permission in app settings."
            is java.net.SocketTimeoutException ->
                "Connection timed out. Check your internet connection and try again."
            is java.io.IOException ->
                "Network error: ${t.message ?: "connection lost"}"
            else -> t.message ?: "Unknown error"
        }
    }
}
