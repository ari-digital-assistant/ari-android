package dev.heyari.ari.reporting

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Posts one content report to the reporting endpoint.
 *
 * Play's AI-Generated Content policy requires reporting to reach the developer
 * "without needing to exit the app", so this is a real request rather than a
 * share sheet or a mailto:. The endpoint is CloudFront in front of an API
 * Gateway and a Lambda that emails the maintainer; nothing about the user goes
 * with it beyond what the dialog showed them.
 */
@HiltWorker
class ReportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val body = buildBody(inputData)
        if (body == null) {
            Log.e(TAG, "report has no text — dropping rather than retrying")
            return@withContext Result.failure()
        }

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            when (val code = connection.responseCode) {
                in 200..299 -> {
                    Log.i(TAG, "report delivered ($code)")
                    Result.success()
                }
                // The payload is wrong and will be just as wrong next time.
                // Retrying would burn battery to be refused identically.
                in 400..499 -> {
                    Log.e(TAG, "report rejected ($code) — not retrying")
                    Result.failure()
                }
                else -> {
                    Log.w(TAG, "report failed ($code) — will retry")
                    Result.retry()
                }
            }
        } catch (t: Throwable) {
            // No network, DNS, TLS, a dead endpoint. All worth another go —
            // the user has been told it is sent, so it had better go.
            Log.w(TAG, "report could not be sent — will retry", t)
            Result.retry()
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "ReportWorker"
        private const val ENDPOINT = "https://heyari.dev/api/report"

        /**
         * Null when there is no text to report, which is the one input the
         * server will not accept. Optional fields are omitted rather than sent
         * as empty strings, so "not recorded" reads as absent in the email.
         */
        internal fun buildBody(data: Data): JSONObject? {
            val text = data.getString(ReportSender.KEY_TEXT)?.takeIf { it.isNotBlank() }
                ?: return null
            return JSONObject().apply {
                put("kind", data.getString(ReportSender.KEY_KIND) ?: "response")
                put("category", data.getString(ReportSender.KEY_CATEGORY) ?: "other")
                put("text", text)
                data.getString(ReportSender.KEY_PROMPT)?.takeIf { it.isNotBlank() }
                    ?.let { put("prompt", it) }
                data.getString(ReportSender.KEY_NOTE)?.takeIf { it.isNotBlank() }
                    ?.let { put("note", it) }
                data.getString(ReportSender.KEY_SKILL_ID)?.takeIf { it.isNotBlank() }
                    ?.let { put("skillId", it) }
                data.getString(ReportSender.KEY_APP_VERSION)?.takeIf { it.isNotBlank() }
                    ?.let { put("appVersion", it) }
            }
        }
    }
}
