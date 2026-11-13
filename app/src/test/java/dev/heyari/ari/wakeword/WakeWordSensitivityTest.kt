package dev.heyari.ari.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordSensitivityTest {

    @Test
    fun `every model gets stricter from HIGH through MEDIUM to LOW`() {
        // The engine fires when sum(recent probabilities) > cutoff * window, so
        // raising either value makes it stricter. Labelled backwards the setting
        // does the opposite of what it says and nothing else in the app notices.
        // Checked per model now that each carries its own numbers: one model
        // ordered correctly says nothing about the next.
        WakeWordRegistry.all.forEach { model ->
            val ladder = WakeWordSensitivity.loosestFirst.map { model.operatingPoint(it) }
            ladder.zipWithNext { looser, stricter ->
                assertTrue(
                    "${model.id} is not monotonic: $looser then $stricter",
                    looser.probabilityCutoff <= stricter.probabilityCutoff &&
                        looser.slidingWindowSize <= stricter.slidingWindowSize,
                )
            }
        }
    }

    @Test
    fun `every level survives int8 quantisation as a distinct threshold`() {
        // MicroWakeWordEngine compares against (uint8)(cutoff * 255), so cutoffs
        // that round to the same byte are the same setting wearing two names.
        // There is no room at all near the ceiling: anything above 0.9981 is 254
        // until it becomes 255 and can never be exceeded.
        WakeWordRegistry.all.forEach { model ->
            val quantised = WakeWordSensitivity.entries.map {
                val point = model.operatingPoint(it)
                (point.probabilityCutoff * 255).toInt() to point.slidingWindowSize
            }
            assertEquals("${model.id} has duplicate levels", quantised.size, quantised.distinct().size)
            assertTrue(
                "${model.id} has a cutoff that quantises to 255 and can never fire",
                quantised.all { it.first < 255 },
            )
        }
    }

    @Test
    fun `the default runs at the operating points its own recordings were scored at`() {
        // Not a restatement of the constants: these three numbers are what the
        // cutoff sweep over the 92 marked takes measured, and a change here
        // without a new measurement is the exact mistake that made a retrained
        // model run on its predecessor's ladder.
        val model = WakeWordRegistry.default

        assertEquals("hey_ari_oww", model.id)
        assertEquals(OperatingPoint(0.50f, 1), model.operatingPoint(WakeWordSensitivity.HIGH))
        assertEquals(OperatingPoint(0.55f, 1), model.operatingPoint(WakeWordSensitivity.MEDIUM))
        assertEquals(OperatingPoint(0.65f, 1), model.operatingPoint(WakeWordSensitivity.LOW))
    }

    @Test
    fun `every default cutoff clears the loudest household ambient we have measured`() {
        // Twenty minutes of household ambient peak at a single score of 0.350
        // for this model. A cutoff at or under that fires on conversation by
        // construction, and no amount of recall makes up for it. The floor can
        // only rise as more rooms are recorded; if it passes a cutoff, the
        // cutoff moves.
        val ambientPeak = 0.350f
        // HIGH's gap, the smallest the ladder has. The exam's own rule was 0.10.
        val minimumMargin = 0.15f
        val model = WakeWordRegistry.default

        WakeWordSensitivity.entries.forEach { level ->
            val point = model.operatingPoint(level)
            assertTrue(
                "${model.id} $level sits at ${point.probabilityCutoff}, too close to household " +
                    "ambient ($ambientPeak)",
                point.probabilityCutoff >= ambientPeak + minimumMargin - 1e-6f,
            )
        }
    }

    @Test
    fun `the unmeasured models keep the ladder the app shipped before this change`() {
        // ok_ari and hey_jarvis have no household recordings to score against.
        // Moving them on hey_ari's evidence would be a guess dressed as a fix.
        listOf("ok_ari", "hey_jarvis").forEach { id ->
            val model = WakeWordRegistry.byId(id)
            assertEquals(OperatingPoint(0.90f, 5), model.operatingPoint(WakeWordSensitivity.HIGH))
            assertEquals(OperatingPoint(0.95f, 5), model.operatingPoint(WakeWordSensitivity.MEDIUM))
            assertEquals(OperatingPoint(0.985f, 10), model.operatingPoint(WakeWordSensitivity.LOW))
        }
    }

    @Test
    fun `the default's points would be nonsense on any other model`() {
        // The reason operating points are per model. The default's strictest
        // setting is far below every other model's loosest, so a shared ladder
        // cannot serve both.
        val default = WakeWordRegistry.default
        val jarvis = WakeWordRegistry.byId("hey_jarvis")

        assertTrue(
            default.operatingPoint(WakeWordSensitivity.LOW).probabilityCutoff <
                jarvis.operatingPoint(WakeWordSensitivity.HIGH).probabilityCutoff,
        )
    }

    @Test
    fun `the default is the middle of the ladder`() {
        assertEquals(WakeWordSensitivity.MEDIUM, WakeWordSensitivity.DEFAULT)
    }

    @Test
    fun `an unknown stored name falls back to the default rather than throwing`() {
        assertEquals(WakeWordSensitivity.DEFAULT, WakeWordSensitivity.fromName("ENTHUSIASTIC"))
        assertEquals(WakeWordSensitivity.DEFAULT, WakeWordSensitivity.fromName(null))
        assertEquals(WakeWordSensitivity.LOW, WakeWordSensitivity.fromName("LOW"))
    }
}
