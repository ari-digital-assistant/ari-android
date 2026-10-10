package dev.heyari.ari.wakeword

/**
 * When to run the speech check on a detection the engine keeps reporting, and
 * which of the resulting drops the wake log records.
 *
 * A detection the check drops is left standing in the engine (see
 * [MicroWakeWord]'s `resetOnDetection`), which then reports again on every
 * inference while its window stays above the cutoff. That is what lets a wake
 * model that fires a moment before the phrase still catch the phrase: the check
 * runs again once the speech has arrived. Running it on every report would cost
 * a speech-model pass every 20-30 ms, so it runs at most every [RECHECK_MS].
 *
 * A run of reports with no gap longer than [EPISODE_GAP_MS] is one episode, and
 * one episode is one dropped wake in the log however many checks it took, so the
 * count stays comparable with the wakes that do open the overlay.
 *
 * Measured offline before it was built: `ari-tools/wakeword/trained/
 * 2026-10-07-vad-gate/RESULTS.md`.
 */
internal class SpeechCheckPacing {
    private var lastReportAt = Long.MIN_VALUE / 2
    private var nextCheckAt = Long.MIN_VALUE
    private var episodeLogged = false

    /** Called on every detection the engine reports. True when the check should run now. */
    fun shouldCheck(now: Long): Boolean {
        if (now - lastReportAt > EPISODE_GAP_MS) {
            episodeLogged = false
            nextCheckAt = now
        }
        lastReportAt = now
        return now >= nextCheckAt
    }

    /** The check found no speech. True for the episode's first drop, the one to log. */
    fun dropped(now: Long): Boolean {
        nextCheckAt = now + RECHECK_MS
        val first = !episodeLogged
        episodeLogged = true
        return first
    }

    /**
     * The check found speech. True when this episode already logged a drop: the
     * wake was let through after all, so that drop prevented nothing.
     */
    fun passed(): Boolean = episodeLogged

    companion object {
        const val RECHECK_MS = 250L
        const val EPISODE_GAP_MS = 1_000L
    }
}
