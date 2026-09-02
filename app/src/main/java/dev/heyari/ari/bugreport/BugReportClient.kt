package dev.heyari.ari.bugreport

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BugReportClient"
private const val BASE = "https://heyari.dev/api/bug"

/**
 * Talks to the bug report endpoint on heyari.dev.
 *
 * Three steps rather than one: create the report and get somewhere to put the
 * files, upload them straight to storage, then finalise, which is what
 * actually files the public issue. Splitting it that way means an issue is
 * never filed pointing at attachments that failed to upload — an issue that
 * links to evidence which does not exist is worse than no issue at all.
 *
 * `HttpURLConnection` because that is what the rest of the app uses; there is
 * no HTTP client dependency here to reach for.
 */
@Singleton
class BugReportClient @Inject constructor() {

    suspend fun send(report: BugReport): SendOutcome = withContext(Dispatchers.IO) {
        try {
            val created = create(report)
            created.uploads.forEach { upload(it) }
            val filed = finalise(created)
            SendOutcome.Filed(filed)
        } catch (rejected: ReportRejected) {
            Log.e(TAG, "report refused: ${rejected.reason}")
            SendOutcome.Rejected(rejected.reason)
        } catch (t: Throwable) {
            Log.w(TAG, "report could not be sent", t)
            SendOutcome.Failed(t)
        }
    }

    /**
     * Withdraws a filed report: erases the stored files and redacts the public
     * issue. Returns false when the server no longer recognises the report,
     * which is what a reporter sees once the 90 days have run out.
     */
    suspend fun withdraw(reportId: String, deleteToken: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("deleteToken", deleteToken)
            try {
                postJson("$BASE/$reportId/delete", body)
                true
            } catch (rejected: ReportRejected) {
                Log.w(TAG, "withdrawal refused: ${rejected.reason}")
                false
            }
        }

    private fun create(report: BugReport): CreatedReport {
        val response = postJson(BASE, report.toWireJson())
            ?: error("the server accepted the report but said nothing back")

        val urls = response.getJSONArray("uploads")
        val byKind = report.attachments.associateBy { it.kind.wireName }
        val uploads = (0 until urls.length()).map { i ->
            val entry = urls.getJSONObject(i)
            val kind = entry.getString("kind")
            val attachment = byKind[kind]
                ?: error("the server offered an upload for $kind, which was never sent")
            PendingUpload(
                kind = attachment.kind,
                url = entry.getString("url"),
                contentType = entry.getString("contentType"),
                file = attachment.file,
            )
        }
        return CreatedReport(
            reportId = response.getString("reportId"),
            deleteToken = response.getString("deleteToken"),
            uploads = uploads,
        )
    }

    /**
     * Straight to storage, never back through the endpoint.
     *
     * The byte count was signed into the URL, so this has to send exactly the
     * file it declared — a file that changed size since it was staged is
     * refused by storage rather than silently truncated.
     */
    private fun upload(pending: PendingUpload) {
        val length = pending.file.length()
        val connection = (URL(pending.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", pending.contentType)
            setFixedLengthStreamingMode(length)
        }
        try {
            connection.outputStream.use { out ->
                pending.file.inputStream().use { it.copyTo(out) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw ReportRejected("upload of ${pending.kind.wireName} refused ($code)")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun finalise(created: CreatedReport): FiledReport {
        val response = postJson("$BASE/${created.reportId}/finalise", JSONObject())
            ?: error("the report was finalised but no issue came back")
        return FiledReport(
            reportId = created.reportId,
            deleteToken = created.deleteToken,
            issueNumber = response.getInt("issueNumber"),
            issueUrl = response.getString("issueUrl"),
        )
    }

    /**
     * Null when the server answered 204, which the withdrawal does.
     *
     * A 4xx is the report's own fault and will be refused identically next
     * time, so it becomes [ReportRejected] rather than something the caller
     * might retry. Anything else is thrown as-is and read as worth retrying.
     */
    private fun postJson(url: String, body: JSONObject): JSONObject? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            if (code in 400..499) {
                throw ReportRejected(readReason(connection))
            }
            if (code !in 200..299) {
                error("the reporting service is unavailable ($code)")
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return text.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The server's own words where it gave any. It never echoes what was sent,
     * so this cannot reflect the reporter's text back at them.
     */
    private fun readReason(connection: HttpURLConnection): String =
        runCatching {
            val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            JSONObject(text).optString("error").takeIf { it.isNotBlank() }
        }.getOrNull() ?: "the report was refused"
}

/** A refusal the reporter has to act on, as opposed to one worth retrying. */
class ReportRejected(val reason: String) : Exception(reason)

/** Staged files live in the cache, so a failed send leaves nothing behind. */
fun stagingDir(cacheDir: File): File = File(cacheDir, "bug-report").apply { mkdirs() }
