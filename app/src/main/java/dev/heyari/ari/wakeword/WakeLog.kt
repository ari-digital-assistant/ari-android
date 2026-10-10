package dev.heyari.ari.wakeword

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How a wake the user could see ended.
 *
 * [falseWake] marks the endings where the overlay opened and nobody had called
 * Ari. Those are what [FalseWakeMonitor] counts and what the log's hourly rate
 * is made of. An error is not one of them: a network failure can sink a
 * request somebody really made.
 */
enum class WakeOutcome(val slug: String, val falseWake: Boolean) {
    /** Passed the name check and went to the engine. */
    ACCEPTED("accepted", false),

    /** Speech, but no "Ari" in it. */
    REJECTED("rejected", true),

    /** The transcriber heard no speech at all: a knock, a buzz, a hum. */
    NO_SPEECH("no-speech", true),

    /** Nothing came back before the silence timeout. */
    SILENT("silent", true),

    /** Transcription failed, or the turn itself did. */
    ERROR("error", false),

    /** Closed before anything else happened, by a tap outside the overlay or by the system. */
    DISMISSED("dismissed", false),

    /** The speech model was still loading, so the user was asked to say it again. */
    COLD_START("cold-start", false),

    /** The overlay could not be opened at all. */
    NOT_SHOWN("not-shown", false);

    companion object {
        fun fromSlug(slug: String): WakeOutcome? = entries.firstOrNull { it.slug == slug }
    }
}

internal const val LOG_LISTEN_ON = "listen-on"
internal const val LOG_LISTEN_OFF = "listen-off"
internal const val LOG_ALIVE = "alive"
internal const val LOG_WAKE = "wake"
internal const val LOG_OUTCOME = "outcome"
internal const val LOG_DROPPED = "dropped"

/** One line of the wake log: when, what, and the words that follow. */
internal data class WakeLogEntry(val at: Long, val kind: String, val args: List<String>)

internal fun formatWakeLogLine(at: Long, kind: String, args: List<String>): String =
    (listOf(at.toString(), kind) + args).joinToString(" ")

/** Null for anything that is not a line this file wrote, a torn last line included. */
internal fun parseWakeLogLine(line: String): WakeLogEntry? {
    val parts = line.trim().split(" ")
    if (parts.size < 2) return null
    val at = parts[0].toLongOrNull() ?: return null
    return WakeLogEntry(at, parts[1], parts.drop(2))
}

/** What one wake model did over a stretch of the log. */
internal data class ModelTally(
    val listeningMs: Long = 0L,
    val wakes: Int = 0,
    val outcomes: Map<WakeOutcome, Int> = emptyMap(),
    /** Detections the speech check dropped before anything opened. Not wakes, not false wakes. */
    val dropped: Int = 0,
) {
    val falseWakes: Int get() = outcomes.filterKeys { it.falseWake }.values.sum()

    /** Wakes with no outcome line: the overlay was replaced by another wake, or the app died. */
    val unresolved: Int get() = wakes - outcomes.values.sum()
}

internal data class WakeLogSummary(
    val byModel: Map<String, ModelTally>,
    /** Listening spells that never logged a stop, because the process was killed. */
    val unclosed: Int,
)

/**
 * Tally the log between [from] and [to], per wake model.
 *
 * Listening time is what makes a false-wake count comparable between phones:
 * ten a day means one thing on a phone that listens all day and another on one
 * that listens for an hour. A spell that never logged a stop ended when its
 * process was killed, and is counted up to the last line written while it was
 * open, which the service's heartbeat keeps within [WakeLog.ALIVE_INTERVAL_MS]
 * of the truth. Only the last spell can still be running, and [listeningNow]
 * says whether it is.
 *
 * An outcome belongs to the wake before it. The two are written from different
 * places, the service and the voice session, and pairing them by order is what
 * keeps the voice session from needing to know which model woke it.
 */
internal fun summariseWakeLog(
    entries: List<WakeLogEntry>,
    from: Long,
    to: Long,
    listeningNow: Boolean,
): WakeLogSummary {
    val listening = mutableMapOf<String, Long>()
    val wakes = mutableMapOf<String, Int>()
    val outcomes = mutableMapOf<String, MutableMap<WakeOutcome, Int>>()
    val dropped = mutableMapOf<String, Int>()
    var unclosed = 0
    var openModel: String? = null
    var openAt = 0L
    var lastSign = 0L
    var pendingWake: String? = null
    var pendingInWindow = false

    // True when the spell overlapped the window at all.
    fun close(end: Long): Boolean {
        val model = openModel ?: return false
        openModel = null
        val overlap = minOf(end, to) - maxOf(openAt, from)
        if (overlap <= 0) return false
        listening.merge(model, overlap, Long::plus)
        return true
    }

    for (entry in entries.sortedBy { it.at }) {
        when (entry.kind) {
            LOG_LISTEN_ON -> {
                if (close(lastSign)) unclosed++
                openModel = entry.args.firstOrNull()
                openAt = entry.at
            }
            LOG_LISTEN_OFF -> close(entry.at)
            LOG_WAKE -> {
                val model = entry.args.firstOrNull() ?: continue
                pendingWake = model
                pendingInWindow = entry.at in from..to
                if (pendingInWindow) wakes.merge(model, 1, Int::plus)
            }
            LOG_DROPPED -> {
                val model = entry.args.firstOrNull() ?: continue
                if (entry.at in from..to) dropped.merge(model, 1, Int::plus)
            }
            LOG_OUTCOME -> {
                val model = pendingWake ?: continue
                pendingWake = null
                val outcome = entry.args.firstOrNull()?.let(WakeOutcome::fromSlug) ?: continue
                if (pendingInWindow) outcomes.getOrPut(model) { mutableMapOf() }.merge(outcome, 1, Int::plus)
            }
        }
        if (openModel != null) lastSign = entry.at
    }
    if (close(if (listeningNow) to else lastSign) && !listeningNow) unclosed++

    val models = listening.keys + wakes.keys + outcomes.keys + dropped.keys
    return WakeLogSummary(
        byModel = models.associateWith { model ->
            ModelTally(
                listeningMs = listening[model] ?: 0L,
                wakes = wakes[model] ?: 0,
                outcomes = outcomes[model].orEmpty(),
                dropped = dropped[model] ?: 0,
            )
        },
        unclosed = unclosed,
    )
}

/** [summary] as the lines a bug report carries, under a heading naming [span]. */
internal fun renderWakeLogSummary(span: String, summary: WakeLogSummary): String = buildString {
    appendLine("--------- wake log, $span")
    if (summary.byModel.isEmpty()) {
        appendLine("nothing recorded")
        return@buildString
    }
    for ((model, tally) in summary.byModel.toSortedMap()) {
        val hours = tally.listeningMs / 3_600_000.0
        append(
            String.format(
                Locale.ROOT, "%s: listening %.1f h, %d wakes, %d false",
                model, hours, tally.wakes, tally.falseWakes,
            )
        )
        if (tally.listeningMs > 0) {
            append(String.format(Locale.ROOT, " (%.2f per listening hour)", tally.falseWakes / hours))
        }
        appendLine()
        val parts = WakeOutcome.entries.mapNotNull { outcome ->
            tally.outcomes[outcome]?.let { "${outcome.slug} $it" }
        } + listOfNotNull(
            tally.unresolved.takeIf { it > 0 }?.let { "no outcome $it" },
            tally.dropped.takeIf { it > 0 }?.let { "dropped for no speech $it" },
        )
        if (parts.isNotEmpty()) appendLine("  " + parts.joinToString(", "))
    }
    if (summary.unclosed > 0) {
        appendLine(
            "${summary.unclosed} listening spells ended without a stop (app killed), " +
                "counted to their last line"
        )
    }
}

/**
 * Every wake, how it ended, and when the microphone was open, on this phone.
 *
 * Exists because the app could not answer the question it most needed to:
 * how often does Ari wake for nothing? The recordings only ever caught some
 * kinds of false wake, and an overlay that closed on a transcription error
 * left no trace anywhere. One line per event, so a tester's bug report carries
 * real numbers rather than an impression.
 *
 * No audio and no transcripts, only times, model names and outcomes, so it is
 * kept whatever the recording settings say. It stays on the phone except in a
 * bug report the user chooses to send.
 *
 * Writes go to the IO dispatcher and are timestamped at the call, so callers
 * on the audio thread and the main thread never wait on the disk, and the
 * reader sorts by time rather than trusting the order lines landed in.
 */
@Singleton
class WakeLog internal constructor(private val file: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, FILE_NAME))

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()

    fun listeningStarted(
        model: WakeWordModel,
        sensitivity: WakeWordSensitivity,
        point: OperatingPoint,
        speechCheck: Boolean,
    ) = record(
        LOG_LISTEN_ON,
        model.id,
        sensitivity.name,
        point.probabilityCutoff.toString(),
        point.slidingWindowSize.toString(),
        if (speechCheck) "speech-check=on" else "speech-check=off",
    )

    fun listeningStopped() = record(LOG_LISTEN_OFF)

    /** The heartbeat that bounds a spell whose process is killed before it can say so. */
    fun stillListening() = record(LOG_ALIVE)

    fun wake(modelId: String) = record(LOG_WAKE, modelId)

    fun outcome(outcome: WakeOutcome) = record(LOG_OUTCOME, outcome.slug)

    /** A detection the speech check dropped: no overlay, no chime, only this line. */
    fun dropped(modelId: String) = record(LOG_DROPPED, modelId)

    private fun record(kind: String, vararg args: String) {
        val line = formatWakeLogLine(System.currentTimeMillis(), kind, args.toList())
        scope.launch { append(line) }
    }

    internal fun append(line: String) = synchronized(lock) {
        try {
            file.appendText(line + "\n")
            if (file.length() > MAX_BYTES) trim()
        } catch (e: IOException) {
            Log.w(TAG, "could not write the wake log", e)
        }
    }

    /**
     * Keep the newest half. Written beside the log and renamed over it, so a
     * process killed halfway through leaves the old log rather than none.
     */
    private fun trim() {
        var kept = 0L
        val tail = file.readLines().asReversed()
            .takeWhile { kept += it.length + 1; kept <= MAX_BYTES / 2 }
            .asReversed()
        val next = File(file.path + ".tmp")
        next.writeText(tail.joinToString("") { it + "\n" })
        if (!next.renameTo(file)) Log.w(TAG, "could not replace the wake log after trimming")
    }

    internal fun entries(): List<WakeLogEntry> = synchronized(lock) {
        try {
            if (file.exists()) file.readLines().mapNotNull(::parseWakeLogLine) else emptyList()
        } catch (e: IOException) {
            Log.w(TAG, "could not read the wake log", e)
            emptyList()
        }
    }

    /**
     * The last day and week summarised, then the week's lines in full for
     * anything the summary did not think to ask. The heartbeat lines are left
     * out of the listing: the summary has already used them.
     */
    fun report(now: Long, listeningNow: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val entries = entries()
        val weekAgo = now - WEEK_MS
        return buildString {
            append(renderWakeLogSummary("last 24 hours", summariseWakeLog(entries, now - DAY_MS, now, listeningNow)))
            append(renderWakeLogSummary("last 7 days", summariseWakeLog(entries, weekAgo, now, listeningNow)))
            appendLine("--------- wake log lines, last 7 days")
            entries.filter { it.at >= weekAgo && it.kind != LOG_ALIVE }
                .sortedBy { it.at }
                .forEach { entry ->
                    val at = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(entry.at).atZone(zone))
                    appendLine((listOf(at, entry.kind) + entry.args).joinToString(" "))
                }
            appendLine()
        }
    }

    companion object {
        private const val TAG = "WakeLog"
        private const val FILE_NAME = "wake-log.txt"

        /**
         * Big enough for about a month of heartbeats, which are most of the
         * lines; a wake-heavy month trims sooner, and the week a report reads
         * is what matters.
         */
        private const val MAX_BYTES = 512L * 1024

        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val WEEK_MS = 7 * DAY_MS

        /** How often a listening service writes [LOG_ALIVE]. */
        const val ALIVE_INTERVAL_MS = 15L * 60 * 1000
    }
}
