package dev.heyari.ari.wakeword

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.audio.AudioClipStore
import dev.heyari.ari.audio.ClipStats
import dev.heyari.ari.audio.clipStem
import javax.inject.Inject
import javax.inject.Singleton

/** Which containment path caught the clip. Recorded in the sidecar. */
enum class WakeCaptureHook(val slug: String) {
    /** The transcript contained speech but no wake-phrase name token. */
    REJECTED("rejected"),

    /** The wake fired and nobody said anything before the silence timeout. */
    SILENT("silent"),
}

/**
 * Persists audio that falsely triggered the wake word, for a future retrain of
 * `hey_ari.tflite` with real hard negatives.
 *
 * App-private storage only, hard-bounded, and gated behind a debug setting that
 * is off by default — the caller checks the setting, this class does not. See
 * `docs/superpowers/specs/2026-07-27-wake-word-false-accept-design.md` §5.
 */
@Singleton
class WakeCaptureStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val clips = AudioClipStore(context, DIR_NAME, MAX_FILES, MAX_BYTES)

    fun save(
        pcm: ShortArray,
        rawTranscript: String,
        hook: WakeCaptureHook,
        timestampMs: Long,
    ) {
        clips.save(clipStem("wake", timestampMs, hook.slug), pcm, rawTranscript)
    }

    fun stats(): ClipStats = clips.stats()

    fun clear() = clips.clear()

    fun shareIntent(): Intent? = clips.shareIntent()

    private companion object {
        const val DIR_NAME = "wake-captures"
        const val MAX_FILES = 50
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}
