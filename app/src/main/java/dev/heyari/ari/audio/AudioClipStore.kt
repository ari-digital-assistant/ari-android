package dev.heyari.ari.audio

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import uniffi.ari_ffi.FfiVoiceException
import uniffi.ari_ffi.saveClip

data class ClipStats(val count: Int, val totalBytes: Long)

/**
 * `<prefix>-<zero-padded epoch ms>-<slug>`, the filename stem every clip and its
 * sidecar share, as the engine's `clip_stem` writes it. Fixed-width and
 * ROOT-formatted because eviction treats lexicographic order as chronological
 * order — a locale with non-ASCII digits would quietly break the ordering.
 */
internal fun clipStem(prefix: String, timestampMs: Long, slug: String): String =
    String.format(Locale.ROOT, "%s-%017d-%s", prefix, timestampMs, slug)

private const val AUTHORITY_SUFFIX = ".captures"

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
 * A directory of captured audio clips under `filesDir`, each a WAV plus a
 * `.txt` sidecar sharing its stem. Storage is app-private. The engine writes
 * them and evicts the oldest past a store's caps, the voice session's
 * captures itself and the wake sample recorder's through [save]; this lists,
 * shares and clears them.
 *
 * Not a Hilt singleton: each capture feature owns one, with its own
 * directory, and adds the domain meaning on top (what the stem encodes, what
 * goes in the sidecar).
 *
 * The primary constructor takes the directory outright, so a JVM test can drive
 * a real store against a `TemporaryFolder` with no Android framework in sight —
 * [context] is null on that path and only [shareIntent] needs it.
 */
class AudioClipStore internal constructor(
    private val context: Context?,
    private val dir: File,
) {
    constructor(context: Context, dirName: String) : this(context, File(context.filesDir, dirName))

    /**
     * Write [pcm] as `<stem>.wav` and [sidecar] as `<stem>.txt`, then evict the
     * oldest pairs until the directory is within [maxFiles] and [maxBytes].
     */
    fun save(stem: String, pcm: ShortArray, sidecar: String, maxFiles: Int, maxBytes: Long) {
        if (pcm.isEmpty()) return
        try {
            saveClip(dir.path, stem, pcmBytes(pcm), sidecar, maxFiles.toUInt(), maxBytes.toULong())
        } catch (e: FfiVoiceException) {
            Log.w(TAG, "Could not capture ${dir.name}/$stem.wav: ${e.message}")
            return
        }
        Log.i(TAG, "Captured ${dir.name}/$stem.wav (${pcm.size} samples)")
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
