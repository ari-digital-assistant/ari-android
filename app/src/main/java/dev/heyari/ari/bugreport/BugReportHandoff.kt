package dev.heyari.ari.bugreport

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries the two things a bug report needs that it cannot fetch for itself.
 *
 * A screenshot has to be taken *before* navigating, because by the time the
 * report screen exists the screen worth capturing is gone. A crash trace comes
 * off disk on the next launch, and consuming it is a one-shot: an offer that
 * reappears every launch is nagging.
 *
 * Neither survives a process death, and neither needs to — the screenshot is
 * of a screen that is no longer showing, and the crash file is still on disk
 * until it is consumed.
 */
@Singleton
class BugReportHandoff @Inject constructor() {

    private var screenshot: ByteArray? = null
    private var crashTrace: String? = null

    fun offer(screenshot: ByteArray? = null, crashTrace: String? = null) {
        this.screenshot = screenshot
        this.crashTrace = crashTrace
    }

    /** Reads and clears, so a second report does not inherit the first's screen. */
    fun take(): Handoff {
        val taken = Handoff(screenshot, crashTrace)
        screenshot = null
        crashTrace = null
        return taken
    }

    data class Handoff(val screenshot: ByteArray?, val crashTrace: String?)
}
