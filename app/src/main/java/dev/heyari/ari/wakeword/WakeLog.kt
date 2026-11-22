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
 * The voice pipeline writes it: the capture loop's lines (listening, wakes,
 * drops) and the voice session's (how each wake ended, its listen, its early
 * name check). What is left here is the one ending no session sees, and the
 * report a bug report carries.
 */
@Singleton
class WakeLog @Inject constructor(@ApplicationContext context: Context) {

    /** The engine's log, which the voice pipeline writes to as well. */
    val engineLog = FfiWakeLog(File(context.filesDir, FILE_NAME).path)

    /** A wake that never reached a session: the overlay could not be opened. */
    fun notShown() = engineLog.outcome(FfiWakeOutcome.NOT_SHOWN, false)

    /** The last day and week summarised, then the week's lines, in the phone's time zone. */
    fun report(now: Long, listeningNow: Boolean): String = engineLog.report(now, listeningNow)

    private companion object {
        const val FILE_NAME = "wake-log.txt"
    }
}
