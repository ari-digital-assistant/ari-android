package dev.heyari.ari.wakeword

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.FfiWakeLog
import uniffi.ari_ffi.FfiWakeOutcome

/**
 * Every wake, how it ended, and when the microphone was open, on this phone:
 * the engine's wake log (ari-voice `wake_log`), in `files/wake-log.txt`.
 *
 * The capture loop's lines (listening, wakes, drops) are written by the voice
 * pipeline itself. What is left here is what the app still decides until the
 * voice session moves to the engine: how a wake ended, its listen, and its
 * early name check, plus the report a bug report carries.
 */
@Singleton
class WakeLog @Inject constructor(@ApplicationContext context: Context) {

    /** The engine's log, which the voice pipeline writes to as well. */
    val engineLog = FfiWakeLog(File(context.filesDir, FILE_NAME).path)

    /** [hidden]: a quiet wake that ended without ever showing anything. */
    fun outcome(outcome: FfiWakeOutcome, hidden: Boolean = false) = engineLog.outcome(outcome, hidden)

    /**
     * How a wake's listen ended ([end] is a `ListenEnd` slug) and [afterMs] from
     * its start, written just before the wake's outcome.
     */
    fun listenEnded(end: String, afterMs: Long) = engineLog.listenEnded(end, afterMs)

    /**
     * A quiet wake's early name check: [result] is an `EarlyNameCheck` slug,
     * [afterMs] how long it took, and [inTime] false when the wake had already
     * been decided, so the result changed nothing.
     */
    fun earlyNameCheck(result: String, afterMs: Long, inTime: Boolean) =
        engineLog.earlyNameCheck(result, afterMs, inTime)

    /** The last day and week summarised, then the week's lines, in the phone's time zone. */
    fun report(now: Long, listeningNow: Boolean): String = engineLog.report(now, listeningNow)

    private companion object {
        const val FILE_NAME = "wake-log.txt"
    }
}
