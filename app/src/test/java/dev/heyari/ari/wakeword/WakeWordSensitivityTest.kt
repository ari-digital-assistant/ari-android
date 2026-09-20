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
    fun `hey_ari runs at the operating points its own recordings were scored at`() {
        // Not a restatement of the constants: these three numbers are what
        // ari-tools/wakeword/baseline measured, and a change here without a new
        // measurement is the exact mistake that made a retrained model run on
        // its predecessor's ladder.
        val model = WakeWordRegistry.byId("hey_ari")

        assertEquals(OperatingPoint(0.65f, 10), model.operatingPoint(WakeWordSensitivity.HIGH))
        assertEquals(OperatingPoint(0.75f, 10), model.operatingPoint(WakeWordSensitivity.MEDIUM))
        assertEquals(OperatingPoint(0.85f, 10), model.operatingPoint(WakeWordSensitivity.LOW))
    }

    @Test
    fun `every hey_ari cutoff clears the loudest household ambient we have measured`() {
        // The check that was missing when a ladder shipped with its strictest
        // setting BELOW the noise floor of an ordinary kitchen. Twenty minutes
        // of household ambient peaks at 0.349 as a mean across a window of 10
        // (ari-tools/wakeword/baseline), and a cutoff at or under that does not
        // need bad luck to false-fire — it fires on conversation, by
        // construction, and no amount of recall makes up for it.
        //
        // The floor is provisional: it can only rise as we record more rooms.
        // If it rises past a cutoff, that cutoff moves. It never sits below.
        val ambientPeak = 0.349f
        // Was 0.10, which is what the ladder that false-fired on LOW carried.
        // A margin the house has already disproved is not a floor worth
        // asserting, so this is the smallest gap still standing.
        val minimumMargin = 0.30f
        val model = WakeWordRegistry.byId("hey_ari")

        WakeWordSensitivity.entries.forEach { level ->
            val point = model.operatingPoint(level)
            assertTrue(
                "hey_ari $level sits at ${point.probabilityCutoff}, which household " +
                    "ambient already reaches ($ambientPeak)",
                point.probabilityCutoff >= ambientPeak + minimumMargin,
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
    fun `hey_ari's points would be nonsense on any other model`() {
        // The reason this refactor exists. hey_ari's MEDIUM is far below every
        // other model's loosest setting, so a shared ladder cannot serve both.
        val heyAri = WakeWordRegistry.byId("hey_ari")
        val jarvis = WakeWordRegistry.byId("hey_jarvis")

        assertTrue(
            heyAri.operatingPoint(WakeWordSensitivity.LOW).probabilityCutoff <
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
