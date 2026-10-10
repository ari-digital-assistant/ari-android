package dev.heyari.ari.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset

class WakeLogTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val hour = 3_600_000L

    private fun on(at: Long, model: String = "hey_ari") =
        WakeLogEntry(at, LOG_LISTEN_ON, listOf(model, "MEDIUM", "0.93", "10"))

    private fun off(at: Long) = WakeLogEntry(at, LOG_LISTEN_OFF, emptyList())
    private fun alive(at: Long) = WakeLogEntry(at, LOG_ALIVE, emptyList())
    private fun wake(at: Long, model: String = "hey_ari") = WakeLogEntry(at, LOG_WAKE, listOf(model))
    private fun outcome(at: Long, outcome: WakeOutcome) = WakeLogEntry(at, LOG_OUTCOME, listOf(outcome.slug))

    @Test
    fun `listening time is clipped to the window`() {
        val entries = listOf(on(0), off(10 * hour), on(20 * hour), off(22 * hour))

        val summary = summariseWakeLog(entries, from = 5 * hour, to = 21 * hour, listeningNow = false)

        assertEquals(6 * hour, summary.byModel.getValue("hey_ari").listeningMs)
        assertEquals(0, summary.unclosed)
    }

    @Test
    fun `a spell its process never closed ends at its last line`() {
        // Killed at some point after the 3h heartbeat; the next process starts at 5h.
        val entries = listOf(on(0), alive(hour), alive(3 * hour), on(5 * hour), off(6 * hour))

        val summary = summariseWakeLog(entries, from = 0, to = 10 * hour, listeningNow = false)

        assertEquals(4 * hour, summary.byModel.getValue("hey_ari").listeningMs)
        assertEquals(1, summary.unclosed)
    }

    @Test
    fun `the last spell runs to now while the microphone is still open`() {
        val entries = listOf(on(0), alive(hour))

        val listening = summariseWakeLog(entries, from = 0, to = 4 * hour, listeningNow = true)
        val killed = summariseWakeLog(entries, from = 0, to = 4 * hour, listeningNow = false)

        assertEquals(4 * hour, listening.byModel.getValue("hey_ari").listeningMs)
        assertEquals(0, listening.unclosed)
        assertEquals(hour, killed.byModel.getValue("hey_ari").listeningMs)
        assertEquals(1, killed.unclosed)
    }

    @Test
    fun `each outcome belongs to the wake before it, per model`() {
        val entries = listOf(
            on(0, "hey_ari"),
            wake(10, "hey_ari"), outcome(11, WakeOutcome.NO_SPEECH),
            wake(20, "hey_ari"), outcome(21, WakeOutcome.ACCEPTED),
            // Replaced by another wake before it ended: no outcome of its own.
            wake(30, "hey_ari"),
            wake(40, "hey_ari"), outcome(41, WakeOutcome.REJECTED),
            off(50),
            on(60, "hey_jarvis"),
            wake(70, "hey_jarvis"), outcome(71, WakeOutcome.SILENT),
            off(80),
        )

        val summary = summariseWakeLog(entries, from = 0, to = 100, listeningNow = false)

        val ari = summary.byModel.getValue("hey_ari")
        assertEquals(4, ari.wakes)
        assertEquals(
            mapOf(WakeOutcome.NO_SPEECH to 1, WakeOutcome.ACCEPTED to 1, WakeOutcome.REJECTED to 1),
            ari.outcomes,
        )
        assertEquals(2, ari.falseWakes)
        assertEquals(1, ari.unresolved)
        val jarvis = summary.byModel.getValue("hey_jarvis")
        assertEquals(1, jarvis.wakes)
        assertEquals(1, jarvis.falseWakes)
    }

    @Test
    fun `dropped detections are counted apart, and never as wakes or false wakes`() {
        val entries = listOf(
            on(0),
            WakeLogEntry(10, LOG_DROPPED, listOf("hey_ari")),
            WakeLogEntry(20, LOG_DROPPED, listOf("hey_ari")),
            wake(30), outcome(31, WakeOutcome.ACCEPTED),
            WakeLogEntry(200, LOG_DROPPED, listOf("hey_ari")),
        )

        val ari = summariseWakeLog(entries, from = 0, to = 100, listeningNow = true).byModel.getValue("hey_ari")

        assertEquals(2, ari.dropped)
        assertEquals(0, ari.passedAfterDrop)
        assertEquals(1, ari.wakes)
        assertEquals(0, ari.falseWakes)
        assertEquals(0, ari.unresolved)
    }

    @Test
    fun `drops let through later and checks that could not run are counted apart`() {
        val entries = listOf(
            on(0),
            WakeLogEntry(10, LOG_DROPPED, listOf("hey_ari")),
            WakeLogEntry(12, LOG_PASSED_AFTER_DROP, listOf("hey_ari")),
            wake(12), outcome(14, WakeOutcome.ACCEPTED),
            WakeLogEntry(20, LOG_CHECK_FAILED, listOf("hey_ari", "error")),
            wake(20), outcome(22, WakeOutcome.NO_SPEECH),
        )

        val ari = summariseWakeLog(entries, from = 0, to = 100, listeningNow = true).byModel.getValue("hey_ari")

        assertEquals(1, ari.dropped)
        assertEquals(1, ari.passedAfterDrop)
        assertEquals(1, ari.checkFailed)
        assertEquals(2, ari.wakes)
        assertEquals(1, ari.falseWakes)
    }

    @Test
    fun `a wake before the window takes its outcome with it`() {
        val entries = listOf(wake(10), outcome(20, WakeOutcome.REJECTED), wake(30), outcome(31, WakeOutcome.ACCEPTED))

        val summary = summariseWakeLog(entries, from = 15, to = 100, listeningNow = false)

        assertEquals(1, summary.byModel.getValue("hey_ari").wakes)
        assertEquals(mapOf(WakeOutcome.ACCEPTED to 1), summary.byModel.getValue("hey_ari").outcomes)
    }

    @Test
    fun `only the visible endings with nobody calling count as false wakes`() {
        assertEquals(
            setOf(WakeOutcome.REJECTED, WakeOutcome.NO_SPEECH, WakeOutcome.SILENT),
            WakeOutcome.entries.filter { it.falseWake }.toSet(),
        )
    }

    @Test
    fun `lines round-trip, and anything else is skipped`() {
        val line = formatWakeLogLine(1_234L, LOG_LISTEN_ON, listOf("hey_ari", "LOW", "0.95", "10"))

        assertEquals("1234 listen-on hey_ari LOW 0.95 10", line)
        assertEquals(
            WakeLogEntry(1_234L, LOG_LISTEN_ON, listOf("hey_ari", "LOW", "0.95", "10")),
            parseWakeLogLine(line),
        )
        assertNull(parseWakeLogLine("12"))
        assertNull(parseWakeLogLine("not-a-time wake hey_ari"))
    }

    @Test
    fun `the summary reads as counts per model and an hourly rate`() {
        val summary = WakeLogSummary(
            byModel = mapOf(
                "hey_ari" to ModelTally(
                    listeningMs = 8 * hour,
                    wakes = 5,
                    outcomes = mapOf(WakeOutcome.ACCEPTED to 1, WakeOutcome.NO_SPEECH to 3),
                    dropped = 7,
                ),
            ),
            unclosed = 2,
        )

        assertEquals(
            "--------- wake log, last day\n" +
                "hey_ari: listening 8.0 h, 5 wakes, 3 false (0.38 per listening hour)\n" +
                "  accepted 1, no-speech 3, no outcome 1, dropped for no speech 7\n" +
                "2 listening spells ended without a stop (app killed), counted to their last line\n",
            renderWakeLogSummary("last day", summary),
        )
        assertEquals(
            "--------- wake log, last day\nnothing recorded\n",
            renderWakeLogSummary("last day", WakeLogSummary(emptyMap(), 0)),
        )
    }

    @Test
    fun `a report summarises the log and lists it without the heartbeats`() {
        val log = WakeLog(File(temp.root, "wake-log.txt"))
        val now = 10 * hour
        log.append(formatWakeLogLine(now - 2 * hour, LOG_LISTEN_ON, listOf("hey_ari", "MEDIUM", "0.93", "10")))
        log.append(formatWakeLogLine(now - hour, LOG_ALIVE, emptyList()))
        log.append(formatWakeLogLine(now - 30 * 60_000, LOG_WAKE, listOf("hey_ari")))
        log.append(formatWakeLogLine(now - 30 * 60_000 + 3_000, LOG_OUTCOME, listOf("no-speech")))

        val report = log.report(now, listeningNow = true, zone = ZoneOffset.UTC)

        assertEquals(
            "--------- wake log, last 24 hours\n" +
                "hey_ari: listening 2.0 h, 1 wakes, 1 false (0.50 per listening hour)\n" +
                "  no-speech 1\n" +
                "--------- wake log, last 7 days\n" +
                "hey_ari: listening 2.0 h, 1 wakes, 1 false (0.50 per listening hour)\n" +
                "  no-speech 1\n" +
                "--------- wake log lines, last 7 days\n" +
                "1970-01-01T08:00:00Z listen-on hey_ari MEDIUM 0.93 10\n" +
                "1970-01-01T09:30:00Z wake hey_ari\n" +
                "1970-01-01T09:30:03Z outcome no-speech\n" +
                "\n",
            report,
        )
    }

    @Test
    fun `a log past its cap keeps its newest lines`() {
        val file = File(temp.root, "wake-log.txt")
        val log = WakeLog(file)
        // Each line is 26 bytes with its newline, so about 20,200 fill 512 KB.
        repeat(25_000) { i -> log.append(formatWakeLogLine(1_000_000_000L + i, LOG_WAKE, listOf("hey_ari_x"))) }

        val entries = log.entries()

        assertTrue(file.length() <= 512L * 1024)
        assertEquals(1_000_000_000L + 24_999, entries.last().at)
        // Contiguous: the trim drops from the front and nowhere else.
        assertEquals((entries.first().at..entries.last().at).toList(), entries.map { it.at })
    }
}
