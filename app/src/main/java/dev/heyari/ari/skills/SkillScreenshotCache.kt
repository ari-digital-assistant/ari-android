package dev.heyari.ari.skills

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads and caches the registry's skill preview screenshots.
 *
 * Screenshots aren't in the signed bundle — they're loose files in the
 * registry, fetched only when someone opens a skill's detail page. They
 * live in `cacheDir` rather than `filesDir` because they're pure
 * decoration: if Android reclaims the space we just fetch them again.
 *
 * Registry screenshot paths carry the skill version
 * (`screenshots/<id>-<version>/<platform>/<file>`), so a skill update
 * publishes new URLs rather than mutating old ones and a cached file can
 * never be stale. That's also why there's no expiry here.
 */
@Singleton
class SkillScreenshotCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val root: File by lazy { File(context.cacheDir, "skill-screenshots") }

    /**
     * The local file for [url], downloading it if we don't already have
     * it. Returns null on any failure — the caller renders a placeholder,
     * because a missing screenshot must never break the detail screen.
     */
    suspend fun fetch(url: String): File? = withContext(Dispatchers.IO) {
        val target = File(root, cacheFileName(url))
        if (target.isFile && target.length() > 0) return@withContext target
        runCatching { download(url, target) }.getOrElse { e ->
            Log.w(TAG, "screenshot fetch failed for $url: ${e.message}")
            null
        }
    }

    private fun download(url: String, target: File): File? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            connect()
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "screenshot fetch returned HTTP ${conn.responseCode}: $url")
                return null
            }
            target.parentFile?.mkdirs()
            // Straight through a temp file so an interrupted download can't
            // leave a truncated image behind that we'd then treat as cached.
            val partial = File(target.parentFile, "${target.name}.part")
            conn.inputStream.use { input -> partial.outputStream().use(input::copyTo) }
            if (partial.length() > MAX_SCREENSHOT_BYTES) {
                Log.w(TAG, "screenshot exceeds ${MAX_SCREENSHOT_BYTES} bytes: $url")
                partial.delete()
                return null
            }
            return if (partial.renameTo(target)) target else null
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TAG = "SkillScreenshotCache"

        /**
         * Ceiling on a single cached image, mirroring the one the skill
         * validator enforces at publish time. Belt and braces: the
         * validator guards our own registry, this guards the device
         * against whatever a fork's registry serves.
         */
        const val MAX_SCREENSHOT_BYTES = 1024 * 1024
    }
}

/**
 * A flat filename for a screenshot URL. The registry path already carries
 * id, version, platform and filename, so flattening the separators gives
 * something legible in a bug report — unlike a hash — while staying one
 * legal filename. Over-long URLs keep their *tail*, because that's the
 * end that distinguishes one screenshot from another; the head is the
 * same registry host for every one of them.
 */
internal fun cacheFileName(url: String): String =
    url.substringAfter("://").map { c -> if (c.isLetterOrDigit() || c == '.' || c == '-') c else '_' }
        .joinToString("")
        .takeLast(120)
