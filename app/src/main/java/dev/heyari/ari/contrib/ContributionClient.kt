package dev.heyari.ari.contrib

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ContributionClient"
private const val BASE = "https://heyari.dev/api/contrib"

/** What one batch of clips did. The worker retries on [Failed] and nothing else. */
sealed interface UploadOutcome {
    /** Every file in the batch reached storage. [lastStem] is the new watermark. */
    data class Sent(val lastStem: String) : UploadOutcome

    /**
     * The server declined the batch and will decline it identically next time —
     * a malformed request, an over-size clip, a rate limit already spent.
     * Retrying wastes the user's battery, so the batch is dropped.
     */
    data class Refused(val reason: String) : UploadOutcome

    /** Network or server trouble. Worth another go on the next Wi-Fi window. */
    data class Failed(val cause: Throwable) : UploadOutcome
}

/** Whether the user's contributions were actually erased. */
sealed interface DeleteOutcome {
    data class Erased(val files: Int) : DeleteOutcome
    data class Refused(val reason: String) : DeleteOutcome
    data class Failed(val cause: Throwable) : DeleteOutcome
}

/**
 * Talks to the contribution endpoint on heyari.dev.
 *
 * Two steps, for the same reason the bug reporter has three: the app never
 * holds an AWS credential. It describes the batch, gets back one pre-signed
 * PUT per file, and writes straight to storage. A key that was never minted
 * cannot be written to, and a URL that was minted expires.
 *
 * `HttpURLConnection` because that is what the rest of the app uses; there is
 * no HTTP client dependency here to reach for.
 */
@Singleton
class ContributionClient @Inject constructor() {

    suspend fun send(batch: ContributionBatch): UploadOutcome = withContext(Dispatchers.IO) {
        if (batch.clips.isEmpty()) return@withContext UploadOutcome.Sent(batch.lastStem)
        try {
            val uploads = mint(batch)
            uploads.forEach { upload(it) }
            UploadOutcome.Sent(batch.lastStem)
        } catch (refused: ContributionRefused) {
            Log.w(TAG, "batch refused: ${refused.reason}")
            UploadOutcome.Refused(refused.reason)
        } catch (t: Throwable) {
            Log.w(TAG, "batch could not be sent", t)
            UploadOutcome.Failed(t)
        }
    }

    /**
     * Erases everything stored under [contributorId]. The id is the only
     * credential: whoever holds it can delete that prefix and nothing else,
     * which is what lets somebody delete from a phone they no longer own.
     */
    suspend fun deleteAll(contributorId: String): DeleteOutcome = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("contributorId", contributorId)
            val response = postJson("$BASE/delete", body)
            DeleteOutcome.Erased(response?.optInt("deleted") ?: 0)
        } catch (refused: ContributionRefused) {
            DeleteOutcome.Refused(refused.reason)
        } catch (t: Throwable) {
            Log.w(TAG, "deletion could not be sent", t)
            DeleteOutcome.Failed(t)
        }
    }

    private fun mint(batch: ContributionBatch): List<PendingUpload> {
        val response = postJson(BASE, batch.toWireJson())
            ?: error("the server accepted the batch but offered nowhere to put it")
        val byName = batch.clips
            .flatMap { listOf(it.audio, it.sidecar) }
            .associateBy { it.name }

        val offered = response.getJSONArray("uploads")
        return (0 until offered.length()).map { i ->
            val entry = offered.getJSONObject(i)
            val name = entry.getString("name")
            val file = byName[name]
                ?: error("the server offered an upload for $name, which was never sent")
            PendingUpload(
                url = entry.getString("url"),
                contentType = entry.getString("contentType"),
                file = file,
            )
        }
    }

    /**
     * Straight to storage, never back through the endpoint.
     *
     * The byte count was signed into the URL, so this has to send exactly the
     * file it declared — a clip evicted between minting and uploading is
     * refused by storage rather than silently truncated.
     */
    private fun upload(pending: PendingUpload) {
        val connection = (URL(pending.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", pending.contentType)
            setFixedLengthStreamingMode(pending.file.length())
        }
        try {
            connection.outputStream.use { out ->
                pending.file.inputStream().use { it.copyTo(out) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw ContributionRefused("upload of ${pending.file.name} refused ($code)")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Null when the server answered 204. A 4xx is the batch's own fault and
     * will be refused identically next time, so it becomes
     * [ContributionRefused] rather than something the worker might retry.
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
            // Rate limiting is the one 4xx worth coming back for: the batch is
            // fine, there has just been too much of it today. Everything else
            // in that range would be refused identically next time.
            if (code == 429) error("the contribution service is rate limiting")
            if (code in 400..499) throw ContributionRefused(readReason(connection))
            if (code !in 200..299) error("the contribution service is unavailable ($code)")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return text.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readReason(connection: HttpURLConnection): String =
        runCatching {
            val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            JSONObject(text).optString("error").takeIf { it.isNotBlank() }
        }.getOrNull() ?: "the contribution was refused"
}

/** A refusal the worker has to stop retrying, as opposed to one worth another go. */
private class ContributionRefused(val reason: String) : Exception(reason)

private class PendingUpload(val url: String, val contentType: String, val file: File)

/** One clip's two files, as the wire describes them. */
data class ContributionClip(
    val category: ContributionCategory,
    val stem: String,
    val audio: File,
    val sidecar: File,
)

/**
 * A run's worth of clips from one capture directory.
 *
 * [lastStem] is what the watermark advances to once the batch lands. It is
 * carried rather than recomputed so the uploader cannot advance the mark past
 * a clip the batch left behind.
 */
data class ContributionBatch(
    val contributorId: String,
    val locale: String,
    val clips: List<ContributionClip>,
    val lastStem: String,
)

/**
 * The body of `POST /api/contrib`.
 *
 * Only the name, category and byte count of each file go here; the bytes
 * themselves go straight to S3 on the pre-signed URLs that come back.
 *
 * The locale rides along because it is what sorts the corpus — a wake clip is
 * only useful next to the other clips in the same language, and the wake
 * sidecar does not record one. There is deliberately nothing else: no device
 * model, no app version, no install id, and no timestamp beyond the one
 * already in each filename. The contributor id is the only thing tying these
 * clips together, which is the entire point of it.
 */
internal fun ContributionBatch.toWireJson(): JSONObject = JSONObject().apply {
    put("contributorId", contributorId)
    put("locale", locale)
    put("files", JSONArray().apply {
        clips.forEach { clip ->
            listOf(clip.audio, clip.sidecar).forEach { file ->
                put(JSONObject().apply {
                    put("category", clip.category.slug)
                    put("name", file.name)
                    put("bytes", file.length())
                })
            }
        }
    })
}
