package dev.heyari.ari.wakeword

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** Which containment path caught the clip. Recorded in the sidecar. */
enum class WakeCaptureHook(val slug: String) {
    /** The transcript contained speech but no wake-phrase name token. */
    REJECTED("rejected"),

    /** The wake fired and nobody said anything before the silence timeout. */
    SILENT("silent"),
}

data class WakeCaptureStats(val count: Int, val totalBytes: Long)

private const val SAMPLE_RATE = 16000
private const val CHANNELS = 1
private const val BYTES_PER_SAMPLE = 2

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
 * Persists audio that falsely triggered the wake word, for a future retrain of
 * `hey_ari.tflite` with real hard negatives.
 *
 * App-private storage only, hard-bounded, and gated behind a debug setting that
 * is off by default — the caller checks the setting, this class does not.
 */
@Singleton
class WakeCaptureStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val dir: File get() = File(context.filesDir, DIR_NAME)

    fun save(
        pcm: ShortArray,
        rawTranscript: String,
        hook: WakeCaptureHook,
        timestampMs: Long,
    ) {
        if (pcm.isEmpty()) return
        val target = dir
        if (!target.exists() && !target.mkdirs()) {
            Log.w(TAG, "Could not create capture directory ${target.path}")
            return
        }
        val stem = "wake-%017d-%s".format(timestampMs, hook.slug)
        val wavFile = File(target, "$stem.wav")
        try {
            wavFile.writeBytes(wavBytes(pcm))
            File(target, "$stem.txt").writeText(rawTranscript)
        } catch (e: IOException) {
            Log.w(TAG, "Could not write capture $stem", e)
            wavFile.delete()
            return
        }
        evictOldest(target, MAX_FILES, MAX_BYTES)
        Log.i(TAG, "Captured false trigger: $stem.wav (${pcm.size} samples)")
    }

    fun stats(): WakeCaptureStats {
        val clips = dir.listFiles { f -> f.extension == "wav" } ?: return WakeCaptureStats(0, 0L)
        return WakeCaptureStats(clips.size, clips.sumOf { it.length() })
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val TAG = "WakeCaptureStore"
        const val DIR_NAME = "wake-captures"
        const val MAX_FILES = 50
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}
