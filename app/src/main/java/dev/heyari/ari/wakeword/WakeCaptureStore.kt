package dev.heyari.ari.wakeword

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.audio.AudioClipStore
import dev.heyari.ari.audio.ClipStats
import dev.heyari.ari.audio.shareIntentFor
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The wake audio the voice session keeps, for a future retrain of the wake
 * word model with real hard negatives. The session (ari-voice `captures`)
 * writes it, behind debug settings that are off by default; this is the
 * settings page's and the bug report's view of it.
 *
 * Clips land in one of three directories depending on which path caught
 * them, and the difference matters more than a filename slug can carry: see
 * [rejectedClips] and [acceptedClips]. Everything here (stats, clear, share)
 * spans all of them. The names are the engine's; they must not drift.
 *
 * The primary constructor takes the directory the stores sit under so a JVM
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
        AudioClipStore(context, File(baseDir, DIR_NAME))

    /**
     * QUARANTINE: the wake fired but the transcript carried no name token. The
     * 2026-07-29 captures proved these are frequently genuine wakes that sherpa
     * misheard ("ARA", "Rind…") — training on them as negatives teaches the
     * model to ignore the user. A human reviews them before ANY clip in here
     * enters a retrain set, which is why they never touch [silentClips]' dir.
     */
    private val rejectedClips =
        AudioClipStore(context, File(baseDir, REJECTED_DIR_NAME))

    /**
     * TRUE POSITIVES: accepted wakes. Kept apart from both dirs above —
     * mixing confirmed positives into either the retrain feed or the
     * quarantine would poison the labels in the other direction.
     */
    private val acceptedClips =
        AudioClipStore(context, File(baseDir, ACCEPTED_DIR_NAME))

    fun stats(): ClipStats {
        val all = listOf(silentClips.stats(), rejectedClips.stats(), acceptedClips.stats())
        return ClipStats(all.sumOf { it.count }, all.sumOf { it.totalBytes })
    }

    fun clear() {
        silentClips.clear()
        rejectedClips.clear()
        acceptedClips.clear()
    }

    fun shareIntent(): Intent? {
        val ctx = context ?: return null
        return shareIntentFor(ctx, files())
    }

    /** Every captured clip and its sidecar, for the share sheet or a bug report. */
    fun files(): List<File> =
        silentClips.files() + rejectedClips.files() + acceptedClips.files()

    // Internal rather than private: ContributionCategory names these
    // directories too, and one definition beats two that can drift apart.
    internal companion object {
        const val DIR_NAME = "wake-captures"
        const val REJECTED_DIR_NAME = "wake-captures-rejected"
        const val ACCEPTED_DIR_NAME = "wake-captures-accepted"
    }
}
