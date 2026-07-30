package dev.heyari.ari.audio

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class ClipStats(val count: Int, val totalBytes: Long)

/**
 * `<prefix>-<zero-padded epoch ms>-<slug>`, the filename stem every clip and its
 * sidecar share. Fixed-width and ROOT-formatted because [evictOldest] treats
 * lexicographic order as chronological order — a locale with non-ASCII digits
 * would quietly break the eviction ordering.
 */
internal fun clipStem(prefix: String, timestampMs: Long, slug: String): String =
    String.format(Locale.ROOT, "%s-%017d-%s", prefix, timestampMs, slug)

private const val SAMPLE_RATE = 16000
private const val CHANNELS = 1
private const val BYTES_PER_SAMPLE = 2
private const val AUTHORITY_SUFFIX = ".captures"

/**
 * Encode [pcm] as a 16-bit mono 16 kHz WAV. That is the format microWakeWord
 * training consumes, so captured clips drop straight into a hard-negative set
 * with no conversion step.
 */
internal fun wavBytes(pcm: ShortArray): ByteArray {
    val dataBytes = pcm.size * 2
    val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray(Charsets.US_ASCII))
    buf.putInt(36 + dataBytes)
    buf.put("WAVE".toByteArray(Charsets.US_ASCII))
    buf.put("fmt ".toByteArray(Charsets.US_ASCII))
    buf.putInt(16)
    buf.putShort(1)
    buf.putShort(CHANNELS.toShort())
    buf.putInt(SAMPLE_RATE)
    buf.putInt(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE)
    buf.putShort((CHANNELS * BYTES_PER_SAMPLE).toShort())
    buf.putShort((BYTES_PER_SAMPLE * 8).toShort())
    buf.put("data".toByteArray(Charsets.US_ASCII))
    buf.putInt(dataBytes)
    for (sample in pcm) buf.putShort(sample)
    return buf.array()
}

/**
 * Delete the oldest clip/sidecar pairs until [dir] is within both caps.
 * Filenames are timestamp-prefixed and fixed-width, so lexicographic order is
 * chronological order.
 */
internal fun evictOldest(dir: File, maxFiles: Int, maxBytes: Long) {
    val clips = dir.listFiles { f -> f.extension == "wav" }?.sortedBy { it.name } ?: return
    var count = clips.size
    var bytes = clips.sumOf { it.length() }
    for (clip in clips) {
        if (count <= maxFiles && bytes <= maxBytes) return
        bytes -= clip.length()
        count--
        clip.delete()
        File(dir, "${clip.nameWithoutExtension}.txt").delete()
    }
}

/**
 * An `ACTION_SEND_MULTIPLE` intent carrying [files] as `.captures` content URIs,
 * or null when there is nothing to share. Top-level rather than a method because
 * a capture feature spanning more than one directory (see
 * [dev.heyari.ari.wakeword.WakeCaptureStore]) shares the authority and the grant
 * flag with the single-directory case, and only one place should know them.
 */
internal fun shareIntentFor(context: Context, files: List<File>): Intent? {
    if (files.isEmpty()) return null
    val authority = "${context.packageName}$AUTHORITY_SUFFIX"
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, authority, it) })
    return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/**
 * A bounded directory of captured audio clips under `filesDir`, each written as
 * a WAV plus a `.txt` sidecar sharing its stem. Storage is app-private and both
 * caps are hard — the oldest pairs are evicted on every write.
 *
 * Not a Hilt singleton: each debug capture feature owns one, with its own
 * directory and caps, and adds the domain meaning on top (what the stem encodes,
 * what goes in the sidecar, which setting gates the write).
 *
 * The primary constructor takes the directory outright, so a JVM test can drive
 * a real store against a `TemporaryFolder` with no Android framework in sight —
 * [context] is null on that path and only [shareIntent] needs it.
 */
class AudioClipStore internal constructor(
    private val context: Context?,
    private val dir: File,
    private val maxFiles: Int,
    private val maxBytes: Long,
) {
    constructor(context: Context, dirName: String, maxFiles: Int, maxBytes: Long) :
        this(context, File(context.filesDir, dirName), maxFiles, maxBytes)

    fun save(stem: String, pcm: ShortArray, sidecar: String) {
        if (pcm.isEmpty()) return
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create capture directory ${dir.path}")
            return
        }
        File(dir, "$stem.wav").writeBytes(wavBytes(pcm))
        File(dir, "$stem.txt").writeText(sidecar)
        // Log the write, then evict: the line reports what this call did, not
        // what survived the caps. The other order reads as a flat lie when a
        // clip large enough to breach maxBytes on its own gets written,
        // evicted, and then announced as captured.
        Log.i(TAG, "Captured ${dir.name}/$stem.wav (${pcm.size} samples)")
        evictOldest(dir, maxFiles, maxBytes)
    }

    fun stats(): ClipStats {
        val clips = dir.listFiles { f -> f.extension == "wav" } ?: return ClipStats(0, 0L)
        return ClipStats(clips.size, clips.sumOf { it.length() })
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Every clip and sidecar, name-sorted — the share sheet's manifest. */
    fun files(): List<File> = dir.listFiles()?.sortedBy { it.name } ?: emptyList()

    /**
     * An `ACTION_SEND_MULTIPLE` intent carrying every clip and its sidecar, or
     * null when there is nothing to share. The caller adds
     * `FLAG_ACTIVITY_NEW_TASK` if launching from a non-activity context.
     */
    fun shareIntent(): Intent? {
        val ctx = context ?: return null
        return shareIntentFor(ctx, files())
    }

    private companion object {
        const val TAG = "AudioClipStore"
    }
}
