package dev.heyari.ari.contrib

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ContributionUploader"

/**
 * How much one run may send. Generous enough to clear a normal day in a single
 * Wi-Fi window, small enough that a phone that has been offline for a month
 * does not upload 50 MB in one go.
 */
private const val MAX_CLIPS_PER_RUN = 25
private const val MAX_BYTES_PER_RUN = 8L * 1024 * 1024

/**
 * One set of recording choices — which categories it covers. The same shape
 * answers both questions the feature asks: what is being kept, and what may be
 * shared. Sharing a category the user is not keeping is meaningless, so the
 * two are always compared rather than read on their own.
 */
data class RecordingChoices(
    val wake: Boolean = false,
    val falseTrigger: Boolean = false,
    val command: Boolean = false,
    val everything: Boolean = false,
) {
    fun covers(category: ContributionCategory): Boolean = everything || when (category) {
        ContributionCategory.WAKE -> wake
        ContributionCategory.FALSE_TRIGGER -> falseTrigger
        ContributionCategory.COMMAND -> command
    }

    /** True when nothing at all is selected — the "don't bother scheduling" case. */
    fun isEmpty(): Boolean = !wake && !falseTrigger && !command && !everything
}

/**
 * The categories that may actually be sent: kept AND shared.
 *
 * Checked here as well as in the UI on purpose. A share toggle left on while
 * its keep toggle goes off must not resurrect clips still sitting on disk from
 * when the user was keeping them.
 */
internal fun contributableCategories(
    keep: RecordingChoices,
    share: RecordingChoices,
): List<ContributionCategory> =
    ContributionCategory.entries.filter { keep.covers(it) && share.covers(it) }

/** What one sweep did. The worker retries on [Retry] and nothing else. */
sealed interface SweepOutcome {
    /** Nothing is shared, or nothing new was waiting. */
    data object Idle : SweepOutcome
    data class Sent(val clips: Int) : SweepOutcome
    /** The network or the server let us down. Try again next window. */
    data object Retry : SweepOutcome
}

/**
 * Sends new capture clips to the contribution corpus, and erases them on
 * request.
 *
 * Nothing here decides *whether* to contribute — the user's toggles do, and
 * this reads them at sweep time so a toggle switched off between windows takes
 * effect immediately. Each capture directory carries a high-water mark of the
 * newest stem already sent, which is what stops a clip being uploaded twice
 * and what lets the directory be evicted underneath us without confusing
 * anything.
 */
@Singleton
class ContributionUploader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val client: ContributionClient,
) {

    private suspend fun keepChoices(): RecordingChoices = RecordingChoices(
        wake = settingsRepository.keepWakeAudio.first(),
        falseTrigger = settingsRepository.keepFalseTriggerAudio.first(),
        command = settingsRepository.keepUtteranceAudio.first(),
        everything = settingsRepository.keepEverythingAudio.first(),
    )

    private suspend fun shareChoices(): RecordingChoices = RecordingChoices(
        wake = settingsRepository.shareWakeAudio.first(),
        falseTrigger = settingsRepository.shareFalseTriggerAudio.first(),
        command = settingsRepository.shareUtteranceAudio.first(),
        everything = settingsRepository.shareEverythingAudio.first(),
    )

    /**
     * Start or stop the periodic sweep to match the user's choices. Called on
     * app start and whenever a share toggle moves, so an install that opted in
     * months ago keeps contributing and one that opted out stops costing
     * anything.
     */
    suspend fun syncSchedule() {
        if (shareChoices().isEmpty()) {
            ContributionWorker.cancel(context)
        } else {
            ContributionWorker.schedule(context)
        }
    }

    /**
     * Mark everything [category] has on disk right now as not for sending.
     *
     * Called the moment its share switch goes on, which is what makes "only
     * recordings made after you agreed" true rather than aspirational. Without
     * it the watermark would still be null on the first sweep and the whole
     * directory — recorded while the user was keeping but explicitly not
     * sharing — would go up.
     *
     * It re-seals on every enable rather than only the first, so switching
     * sharing off and on again does not hand over the clips made in between.
     * The cost is a handful of clips that were waiting to go when the switch
     * was flicked, which is the right way round for this feature to err.
     */
    private suspend fun sealExistingClips(category: ContributionCategory) {
        for (dirName in category.dirNames) {
            val newest = pendingClips(File(context.filesDir, dirName), watermark = null)
                .lastOrNull() ?: continue
            settingsRepository.setContributionWatermark(dirName, newest.stem)
            Log.i(TAG, "sealed $dirName at ${newest.stem}")
        }
    }

    /** Seal the categories a freshly-enabled share switch covers. */
    suspend fun sealExistingClips(enabled: RecordingChoices) {
        ContributionCategory.entries
            .filter { enabled.covers(it) }
            .forEach { sealExistingClips(it) }
    }

    suspend fun sweep(): SweepOutcome {
        val categories = contributableCategories(keepChoices(), shareChoices())
        if (categories.isEmpty()) return SweepOutcome.Idle

        val contributorId = settingsRepository.contributorId()
        val locale = settingsRepository.activeLocale.first()
        var slots = MAX_CLIPS_PER_RUN
        var budget = MAX_BYTES_PER_RUN
        var sent = 0
        var failed = false

        for (category in categories) {
            for (dirName in category.dirNames) {
                if (slots == 0 || budget <= 0) break
                val dir = File(context.filesDir, dirName)
                val watermark = settingsRepository.contributionWatermark(dirName).first()
                val clips = mutableListOf<ContributionClip>()
                for (pending in pendingClips(dir, watermark)) {
                    if (slots == 0) break
                    val size = pending.audio.length() + pending.sidecar.length()
                    // Always take one, even an oversized clip, so a single fat
                    // recording can never wedge the queue behind it.
                    if (size > budget && clips.isNotEmpty()) break
                    clips += ContributionClip(category, pending.stem, pending.audio, pending.sidecar)
                    slots--
                    budget -= size
                }
                if (clips.isEmpty()) continue

                val batch = ContributionBatch(
                    contributorId = contributorId,
                    locale = locale,
                    clips = clips,
                    lastStem = clips.last().stem,
                )
                when (val outcome = client.send(batch)) {
                    is UploadOutcome.Sent -> {
                        settingsRepository.setContributionWatermark(dirName, outcome.lastStem)
                        settingsRepository.setHasSharedRecordings(true)
                        sent += clips.size
                        Log.i(TAG, "sent ${clips.size} clip(s) from $dirName")
                    }
                    is UploadOutcome.Refused -> {
                        // The server will say the same thing next time, so step
                        // the mark past this batch rather than retrying it for
                        // ever. Rate limiting answers 429 and lands on Failed.
                        settingsRepository.setContributionWatermark(dirName, batch.lastStem)
                        Log.w(TAG, "dropped ${clips.size} clip(s) from $dirName: ${outcome.reason}")
                    }
                    is UploadOutcome.Failed -> {
                        failed = true
                        Log.w(TAG, "could not send from $dirName", outcome.cause)
                    }
                }
            }
        }

        return when {
            failed -> SweepOutcome.Retry
            sent > 0 -> SweepOutcome.Sent(sent)
            else -> SweepOutcome.Idle
        }
    }

    /**
     * Erase everything this contributor has sent, and stop sending more.
     *
     * Turning the share toggles off is part of the operation rather than a
     * separate step the user has to remember: deleting while still sharing
     * would simply re-upload the same clips on the next Wi-Fi window. The
     * watermarks are kept for the same reason — clearing them would offer
     * every clip still on disk a second time, which is precisely the audio the
     * user just asked us to forget. The contributor id is kept too, so someone
     * who opts back in later can still delete what they send from then on.
     */
    suspend fun deleteEverythingShared(): DeleteOutcome {
        val outcome = client.deleteAll(settingsRepository.contributorId())
        if (outcome !is DeleteOutcome.Erased) return outcome
        settingsRepository.setShareWakeAudio(false)
        settingsRepository.setShareFalseTriggerAudio(false)
        settingsRepository.setShareUtteranceAudio(false)
        settingsRepository.setShareEverythingAudio(false)
        settingsRepository.setHasSharedRecordings(false)
        ContributionWorker.cancel(context)
        return outcome
    }
}
