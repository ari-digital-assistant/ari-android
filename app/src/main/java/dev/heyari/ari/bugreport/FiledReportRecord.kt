package dev.heyari.ari.bugreport

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A report this phone has filed, as My Reports lists it.
 *
 * The [deleteToken] is the only thing that can withdraw the report, and it
 * exists nowhere else — the server keeps a hash of it and nothing more. That
 * makes this list the whole of a reporter's ability to change their mind, and
 * it is why the consent text says plainly that uninstalling Ari leaves only
 * the automatic 90-day deletion.
 */
data class FiledReportRecord(
    val reportId: String,
    val deleteToken: String,
    val issueNumber: Int,
    val issueUrl: String,
    val title: String,
    val filedAtMillis: Long,
) {
    /**
     * When the stored files go, whether or not anybody asks. Derived rather
     * than stored so it can never disagree with the server's own rule.
     */
    val expiresAtMillis: Long get() = filedAtMillis + RETENTION_MILLIS

    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis

    companion object {
        const val RETENTION_DAYS = 90
        const val RETENTION_MILLIS = RETENTION_DAYS * 24L * 60L * 60L * 1000L

        /** Keeps the list finite on a phone that reports a lot. */
        const val MAX_RECORDS = 50
    }
}

internal fun encodeReports(reports: List<FiledReportRecord>): String {
    val array = JSONArray()
    reports.forEach { report ->
        array.put(
            JSONObject().apply {
                put("id", report.reportId)
                put("token", report.deleteToken)
                put("issue", report.issueNumber)
                put("url", report.issueUrl)
                put("title", report.title)
                put("at", report.filedAtMillis)
            }
        )
    }
    return array.toString()
}

internal fun decodeReports(raw: String?): List<FiledReportRecord> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            FiledReportRecord(
                reportId = id,
                deleteToken = obj.optString("token"),
                issueNumber = obj.optInt("issue"),
                issueUrl = url,
                title = obj.optString("title"),
                filedAtMillis = obj.optLong("at"),
            )
        }
    } catch (e: JSONException) {
        // Losing the list costs a tester the ability to withdraw by hand; the
        // 90-day deletion still runs. Dropping it beats crashing the settings
        // screen on a corrupt string.
        Log.w(TAG, "Corrupt report store — dropping the list", e)
        emptyList()
    }
}

private const val TAG = "FiledReportRecord"
