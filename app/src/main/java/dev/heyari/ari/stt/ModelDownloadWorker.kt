package dev.heyari.ari.stt

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * WorkManager-backed STT model downloader. Survives process death —
 * WorkManager persists the enqueued work in its own SQLite store and
 * re-schedules it after the process restarts.
 *
 * Progress is reported via [setProgress] and observed from the UI via
 * [ModelDownloadManager.stateFlow].
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
        val files = listOf(model.encoderFile, model.decoderFile, model.joinerFile, model.tokensFile)

        try {
            var bytesSoFar = 0L
            for (file in files) {
                val target = File(dir, file)
                if (target.isFile && target.length() > 0L) {
                    bytesSoFar += target.length()
                    setProgress(workDataOf(
                        KEY_BYTES_SO_FAR to bytesSoFar,
                        KEY_TOTAL_BYTES to model.totalBytes,
                        KEY_MODEL_ID to modelId,
                    ))
                    continue
                }

                val url = "${model.baseUrl}/$file"
                Log.i(TAG, "Downloading $url -> ${target.absolutePath}")

                val downloaded = downloadFile(url, target, modelId, bytesSoFar, model.totalBytes)
                if (isStopped) {
                    target.delete()
                    return@withContext Result.failure()
                }
                bytesSoFar += downloaded
            }

            setProgress(workDataOf(
                KEY_BYTES_SO_FAR to model.totalBytes,
                KEY_TOTAL_BYTES to model.totalBytes,
                KEY_MODEL_ID to modelId,
            ))
            Log.i(TAG, "Download completed for $modelId")
            Result.success(workDataOf(KEY_MODEL_ID to modelId))
        } catch (t: Throwable) {
            Log.e(TAG, "Download failed for $modelId: ${t.message}", t)
            Result.failure(workDataOf(
                KEY_MODEL_ID to modelId,
                KEY_ERROR to friendlyError(t),
            ))
        }
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        modelId: String,
        baseBytesSoFar: Long,
        totalBytes: Long,
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
