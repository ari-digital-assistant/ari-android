package dev.heyari.ari.llm

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
 * WorkManager-backed LLM model downloader. Same pattern as
 * [dev.heyari.ari.stt.ModelDownloadWorker] — survives process death,
 * reports progress via [setProgress].
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
                                    KEY_TOTAL_BYTES to model.totalBytes,
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
            } finally {
                connection.disconnect()
            }

            Log.i(TAG, "LLM download completed for $modelId (${target.length()} bytes)")
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
