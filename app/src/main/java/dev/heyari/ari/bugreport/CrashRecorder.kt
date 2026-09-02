package dev.heyari.ari.bugreport

import android.content.Context
import android.os.Build
import dev.heyari.ari.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

private const val FILE_NAME = "last-crash.txt"

/** How much of a stack trace is worth keeping. Deep enough for any real one. */
private const val MAX_TRACE_CHARS = 16_000

/**
 * Writes a crash to disk on the way down, so the next launch can offer to
 * report it.
 *
 * This exists because a tester cannot tap a bug-report button in a process
 * that has already died. Without it we would only ever receive reports for
 * bugs that did *not* crash the app — which are precisely the ones we least
 * need help finding.
 *
 * Everything here happens inside a dying process, so it is deliberately dull:
 * one synchronous write of a string already in memory, no dependency
 * injection, no coroutines, no network. Then the previous handler runs and the
 * process goes exactly as it would have.
 */
object CrashRecorder {

    fun install(context: Context) {
        // Nothing to install in a build with no way to report the result.
        if (!BuildConfig.ARI_TESTING) return

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val file = crashFile(context)
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // A failure while recording must never replace the crash the user
            // actually hit, so this swallows its own trouble and hands over.
            runCatching { file.writeText(render(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * The crash from last time, if there was one. Reading consumes it: an
     * offer that reappears every launch until it is accepted is nagging, and
     * the report screen already has the text by then.
     */
    fun consume(context: Context): String? {
        val file = crashFile(context)
        if (!file.exists()) return null
        val trace = runCatching { file.readText() }.getOrNull()
        file.delete()
        return trace?.takeIf { it.isNotBlank() }
    }

    private fun crashFile(context: Context) = File(context.filesDir, FILE_NAME)

    /**
     * Class names, line numbers, and the version they came from. No message
     * from the user, nothing from their data — a stack trace is safe to
     * publish precisely because of what it does not contain, and that only
     * stays true if nothing else is added here.
     */
    private fun render(thread: Thread, error: Throwable): String {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("Ari ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            appendLine("crashed on thread '${thread.name}' at ${Instant.now()}")
            appendLine()
            append(stack.take(MAX_TRACE_CHARS))
        }
    }
}
