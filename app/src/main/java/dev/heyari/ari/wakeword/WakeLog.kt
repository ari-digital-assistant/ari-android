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
internal const val LOG_PASSED_AFTER_DROP = "passed-after-drop"
internal const val LOG_CHECK_FAILED = "speech-check-failed"
internal const val LOG_LISTEN_END = "listen-end"
internal const val LOG_EARLY_NAME = "early-name"
internal const val LOG_SHADOW_WAKE = "shadow-wake"
internal const val LOG_SHADOW_DROPPED = "shadow-dropped"
internal const val LOG_SHADOW_LABEL = "shadow-label"

/** How close to a live wake a shadow model's detection counts as hearing the same call. */
internal const val SHADOW_WITH_LIVE_MS = 3_000L

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
    /** Drops whose episode was let through on a re-check after all: prevented nothing. */
    val passedAfterDrop: Int = 0,
    /** Wakes let through unchecked because the check could not load or threw. */
    val checkFailed: Int = 0,
    /** How quiet wakes ended that never showed anything. Not in [outcomes]. */
    val hidden: Map<WakeOutcome, Int> = emptyMap(),
) {
    /** False wakes somebody saw. The ones a quiet wake hid are [hiddenFalseWakes]. */
    val falseWakes: Int get() = outcomes.filterKeys { it.falseWake }.values.sum()

    val hiddenFalseWakes: Int get() = hidden.filterKeys { it.falseWake }.values.sum()

    /** Wakes with no outcome line: the overlay was replaced by another wake, or the app died. */
    val unresolved: Int get() = wakes - outcomes.values.sum() - hidden.values.sum()
}

/**
 * What a shadow model did over a stretch of the log: where it would have woken
 * Ari, with nothing ever shown. Labels come from transcribing the 2 s before
 * each detection: `name` (the name check passed), `no-name`, `none` (nothing
 * transcribed), `unlabelled` (no cloud transcription to ask).
 */
internal data class ShadowTally(
    val listeningMs: Long = 0L,
    val detections: Int = 0,
    val labels: Map<String, Int> = emptyMap(),
    /** Detections its speech check dropped: not detections. */
    val dropped: Int = 0,
    /** Live wakes Ari accepted, a real call each, while this model ran beside them. */
    val liveAccepted: Int = 0,
    /** Of [liveAccepted], those it reacted to within [SHADOW_WITH_LIVE_MS]. */
    val withLiveAccepted: Int = 0,
)

internal data class WakeLogSummary(
    val byModel: Map<String, ModelTally>,
    /** Listening spells that never logged a stop, because the process was killed. */
    val unclosed: Int,
    val shadow: Map<String, ShadowTally> = emptyMap(),
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
    val passedAfterDrop = mutableMapOf<String, Int>()
    val checkFailed = mutableMapOf<String, Int>()
    val hidden = mutableMapOf<String, MutableMap<WakeOutcome, Int>>()
    var unclosed = 0
    var openModel: String? = null
    var openAt = 0L
    var lastSign = 0L
    var pendingWake: String? = null
    var pendingWakeAt = 0L
    var pendingInWindow = false
    // The shadow model running in the open spell, if any.
    var openShadow: String? = null
    val shadowListening = mutableMapOf<String, Long>()
    val shadowDetections = mutableMapOf<String, Int>()
    val shadowLabels = mutableMapOf<String, MutableMap<String, Int>>()
    val shadowDropped = mutableMapOf<String, Int>()
    val shadowEvents = mutableMapOf<String, MutableList<Long>>()
    val acceptedWithShadow = mutableListOf<Pair<Long, String>>()

    // True when the spell overlapped the window at all.
    fun close(end: Long): Boolean {
        val model = openModel ?: return false
        openModel = null
        val shadowModel = openShadow
        openShadow = null
        val overlap = minOf(end, to) - maxOf(openAt, from)
        if (overlap <= 0) return false
        listening.merge(model, overlap, Long::plus)
        if (shadowModel != null) shadowListening.merge(shadowModel, overlap, Long::plus)
        return true
    }

    for (entry in entries.sortedBy { it.at }) {
        when (entry.kind) {
            LOG_LISTEN_ON -> {
                if (close(lastSign)) unclosed++
                openModel = entry.args.firstOrNull()
                openShadow = shadowOf(entry)
                openAt = entry.at
            }
            LOG_LISTEN_OFF -> close(entry.at)
            LOG_SHADOW_WAKE, LOG_SHADOW_DROPPED, LOG_SHADOW_LABEL -> {
                val model = entry.args.firstOrNull() ?: continue
                if (entry.kind != LOG_SHADOW_LABEL) shadowEvents.getOrPut(model) { mutableListOf() }.add(entry.at)
                if (entry.at !in from..to) continue
                when (entry.kind) {
                    LOG_SHADOW_WAKE -> shadowDetections.merge(model, 1, Int::plus)
                    LOG_SHADOW_DROPPED -> shadowDropped.merge(model, 1, Int::plus)
                    else -> entry.args.getOrNull(1)?.let {
                        shadowLabels.getOrPut(model) { mutableMapOf() }.merge(it, 1, Int::plus)
                    }
                }
            }
            LOG_WAKE -> {
                val model = entry.args.firstOrNull() ?: continue
                pendingWake = model
                pendingWakeAt = entry.at
                pendingInWindow = entry.at in from..to
                if (pendingInWindow) wakes.merge(model, 1, Int::plus)
            }
            LOG_DROPPED, LOG_PASSED_AFTER_DROP, LOG_CHECK_FAILED -> {
                val model = entry.args.firstOrNull() ?: continue
                if (entry.at !in from..to) continue
                val tally = when (entry.kind) {
                    LOG_DROPPED -> dropped
                    LOG_PASSED_AFTER_DROP -> passedAfterDrop
                    else -> checkFailed
                }
                tally.merge(model, 1, Int::plus)
            }
            LOG_OUTCOME -> {
                val model = pendingWake ?: continue
                pendingWake = null
                val outcome = entry.args.firstOrNull()?.let(WakeOutcome::fromSlug) ?: continue
                val into = if (entry.args.getOrNull(1) == "hidden") hidden else outcomes
                if (pendingInWindow) into.getOrPut(model) { mutableMapOf() }.merge(outcome, 1, Int::plus)
                val shadowModel = openShadow
                if (pendingInWindow && outcome == WakeOutcome.ACCEPTED && shadowModel != null) {
                    acceptedWithShadow.add(pendingWakeAt to shadowModel)
                }
            }
        }
        if (openModel != null) lastSign = entry.at
    }
    if (close(if (listeningNow) to else lastSign) && !listeningNow) unclosed++

    val models = listening.keys + wakes.keys + outcomes.keys + dropped.keys + checkFailed.keys
    val shadowModels = shadowListening.keys + shadowDetections.keys + shadowDropped.keys
    val shadow = shadowModels.associateWith { model ->
        val accepted = acceptedWithShadow.filter { it.second == model }.map { it.first }
        val events = shadowEvents[model].orEmpty()
        ShadowTally(
            listeningMs = shadowListening[model] ?: 0L,
            detections = shadowDetections[model] ?: 0,
            labels = shadowLabels[model].orEmpty(),
            dropped = shadowDropped[model] ?: 0,
            liveAccepted = accepted.size,
            withLiveAccepted = accepted.count { t -> events.any { kotlin.math.abs(it - t) <= SHADOW_WITH_LIVE_MS } },
        )
    }
    return WakeLogSummary(
        byModel = models.associateWith { model ->
            ModelTally(
                listeningMs = listening[model] ?: 0L,
                wakes = wakes[model] ?: 0,
                outcomes = outcomes[model].orEmpty(),
                dropped = dropped[model] ?: 0,
                passedAfterDrop = passedAfterDrop[model] ?: 0,
                checkFailed = checkFailed[model] ?: 0,
                hidden = hidden[model].orEmpty(),
            )
        },
        unclosed = unclosed,
        shadow = shadow,
    )
}

/** The shadow model a listen-on line names, as `shadow=<id>/...`, or null. */
private fun shadowOf(entry: WakeLogEntry): String? =
    entry.args.firstOrNull { it.startsWith("shadow=") }?.removePrefix("shadow=")?.substringBefore('/')

/** [summary] as the lines a bug report carries, under a heading naming [span]. */
internal fun renderWakeLogSummary(span: String, summary: WakeLogSummary): String = buildString {
    appendLine("--------- wake log, $span")
    if (summary.byModel.isEmpty() && summary.shadow.isEmpty()) {
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
            tally.passedAfterDrop.takeIf { it > 0 }?.let { "of those, let through on a re-check $it" },
            tally.checkFailed.takeIf { it > 0 }?.let { "let through unchecked $it" },
        )
        if (parts.isNotEmpty()) appendLine("  " + parts.joinToString(", "))
        val quiet = WakeOutcome.entries.mapNotNull { outcome ->
            tally.hidden[outcome]?.let { "${outcome.slug} $it" }
        }
        if (quiet.isNotEmpty()) appendLine("  never shown (quiet wake): " + quiet.joinToString(", "))
    }
    for ((model, tally) in summary.shadow.toSortedMap()) {
        val hours = tally.listeningMs / 3_600_000.0
        append(String.format(Locale.ROOT, "shadow %s: listening %.1f h, %d detections", model, hours, tally.detections))
        if (tally.listeningMs > 0) {
            append(String.format(Locale.ROOT, " (%.2f per listening hour)", tally.detections / hours))
        }
        appendLine()
        val parts = listOf("name", "no-name", "none", "unlabelled").mapNotNull { label ->
            tally.labels[label]?.let { "$label $it" }
        } + listOfNotNull(
            tally.dropped.takeIf { it > 0 }?.let { "dropped for no speech $it" },
            tally.liveAccepted.takeIf { it > 0 }?.let {
                "fired with ${tally.withLiveAccepted} of $it accepted live wakes"
            },
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

    /**
     * [fingerprint] is the start of the model file's SHA-256, so a result can be
     * traced to exactly the file that ran. [shadow] is the model running beside
     * it, written into the same line so the two can never be paired wrongly.
     */
    fun listeningStarted(
        model: WakeWordModel,
        sensitivity: WakeWordSensitivity,
        point: OperatingPoint,
        speechCheck: Boolean,
        quietWake: Boolean,
        fingerprint: String,
        shadow: ShadowRun? = null,
    ) = record(
        LOG_LISTEN_ON,
        *listOfNotNull(
            model.id,
            sensitivity.name,
            point.probabilityCutoff.toString(),
            point.slidingWindowSize.toString(),
            if (speechCheck) "speech-check=on" else "speech-check=off",
            if (quietWake) "quiet-wake=on" else "quiet-wake=off",
            "file=$fingerprint",
            shadow?.let {
                "shadow=${it.model.id}/${it.sensitivity.name}/${it.point.probabilityCutoff}/" +
                    "${it.point.slidingWindowSize}/${it.fingerprint}"
            },
        ).toTypedArray(),
    )

    /** The shadow model detected the wake word, and its speech check let it through. */
    fun shadowWake(modelId: String) = record(LOG_SHADOW_WAKE, modelId)

    /** The shadow model detected it, and its speech check dropped it. */
    fun shadowDropped(modelId: String) = record(LOG_SHADOW_DROPPED, modelId)

    /** What the transcript of a shadow detection's 2 s says: see [ShadowTally]. */
    fun shadowLabel(modelId: String, label: String) = record(LOG_SHADOW_LABEL, modelId, label)

    fun listeningStopped() = record(LOG_LISTEN_OFF)

    /** The heartbeat that bounds a spell whose process is killed before it can say so. */
    fun stillListening() = record(LOG_ALIVE)

    fun wake(modelId: String) = record(LOG_WAKE, modelId)

    /** [hidden]: a quiet wake that ended without ever showing anything. */
    fun outcome(outcome: WakeOutcome, hidden: Boolean = false) =
        if (hidden) record(LOG_OUTCOME, outcome.slug, "hidden") else record(LOG_OUTCOME, outcome.slug)

    /** A detection the speech check dropped: no overlay, no chime, only this line. */
    fun dropped(modelId: String) = record(LOG_DROPPED, modelId)

    /** The check found speech in an episode it had already dropped once. */
    fun passedAfterDrop(modelId: String) = record(LOG_PASSED_AFTER_DROP, modelId)

    /**
     * A wake let through because the check could not run: [reason] is
     * `unavailable` (the model would not load) or `error` (it threw). Kept apart
     * from drops so a trial can show which protection actually ran.
     */
    fun speechCheckFailed(modelId: String, reason: String) = record(LOG_CHECK_FAILED, modelId, reason)

    /**
     * How a wake's listen ended ([end] is a `ListenEnd` slug) and [afterMs] from
     * its start, written just before the wake's outcome. Shows on the phone what
     * the offline test could only estimate: how long listening takes.
     */
    fun listenEnded(end: String, afterMs: Long) = record(LOG_LISTEN_END, end, afterMs.toString())

    /**
     * A quiet wake's early name check: [result] is an `EarlyNameCheck` slug,
     * [afterMs] how long it took, and `late` follows when the wake had already
     * been decided, so the result changed nothing.
     */
    fun earlyNameCheck(result: String, afterMs: Long, inTime: Boolean) =
        if (inTime) record(LOG_EARLY_NAME, result, afterMs.toString())
        else record(LOG_EARLY_NAME, result, afterMs.toString(), "late")

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
