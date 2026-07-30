package dev.heyari.ari.wakeword

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.audio.AudioClipStore
import dev.heyari.ari.audio.ClipStats
import dev.heyari.ari.audio.clipStem
import dev.heyari.ari.audio.shareIntentFor
import java.io.File
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
 *
 * Clips land in one of two directories depending on which hook caught them, and
 * the difference matters more than a filename slug can carry — see
 * [rejectedClips]. Everything the settings page does (stats, clear, share) spans
 * both.
 *
 * The primary constructor takes the directory the two stores sit under so a JVM
 * test can assert the routing against a `TemporaryFolder`; [context] is null on
 * that path and only [shareIntent] needs it.
 */
@Singleton
class WakeCaptureStore internal constructor(
    private val context: Context?,
    baseDir: File,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, context.filesDir)

    /** Hard-negative candidates: the wake fired and nobody spoke. Safe retrain feed. */
    private val silentClips =
        AudioClipStore(context, File(baseDir, DIR_NAME), MAX_FILES, MAX_BYTES)

    /**
     * QUARANTINE: the wake fired but the transcript carried no name token. The
     * 2026-07-29 captures proved these are frequently genuine wakes that sherpa
     * misheard ("ARA", "Rind…") — training on them as negatives teaches the
     * model to ignore the user. A human reviews them before ANY clip in here
     * enters a retrain set, which is why they never touch [silentClips]' dir.
     */
    private val rejectedClips =
        AudioClipStore(context, File(baseDir, REJECTED_DIR_NAME), MAX_FILES, MAX_BYTES)

    fun save(
        pcm: ShortArray,
        rawTranscript: String,
        hook: WakeCaptureHook,
        timestampMs: Long,
    ) {
        val clips = if (hook == WakeCaptureHook.REJECTED) rejectedClips else silentClips
        clips.save(clipStem("wake", timestampMs, hook.slug), pcm, rawTranscript)
    }

    fun stats(): ClipStats {
        val silent = silentClips.stats()
        val rejected = rejectedClips.stats()
        return ClipStats(silent.count + rejected.count, silent.totalBytes + rejected.totalBytes)
    }

    fun clear() {
        silentClips.clear()
        rejectedClips.clear()
    }

    fun shareIntent(): Intent? {
        val ctx = context ?: return null
        return shareIntentFor(ctx, silentClips.files() + rejectedClips.files())
    }

    private companion object {
        const val DIR_NAME = "wake-captures"
        const val REJECTED_DIR_NAME = "wake-captures-rejected"
        const val MAX_FILES = 50
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}
