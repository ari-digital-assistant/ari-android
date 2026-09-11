package dev.heyari.ari.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordSensitivityTest {

    @Test
    fun `strictness increases from HIGH through MEDIUM to LOW`() {
        // The engine fires when sum(recent probabilities) > cutoff * window, so
        // raising either value makes it stricter. HIGH must therefore be no
        // stricter than MEDIUM on both axes, and MEDIUM no stricter than LOW.
        // Labelled backwards this setting does the opposite of what it says,
        // and nothing else in the app would notice.
        val ladder = listOf(
            WakeWordSensitivity.HIGH,
            WakeWordSensitivity.MEDIUM,
            WakeWordSensitivity.LOW,
        )

        ladder.zipWithNext { looser, stricter ->
            assertTrue(
                "${looser.name} must not be stricter than ${stricter.name}",
                looser.probabilityCutoff <= stricter.probabilityCutoff &&
                    looser.slidingWindowSize <= stricter.slidingWindowSize,
            )
        }
    }

    @Test
    fun `every level survives int8 quantisation as a distinct threshold`() {
        // MicroWakeWordEngine compares against (uint8)(cutoff * 255), so cutoffs
        // that round to the same byte are the same setting wearing two names.
        // Near the ceiling there is very little room: 0.985, 0.99, 0.995 and
        // 0.999 land on 251, 252, 253 and 254 respectively, and anything above
        // 0.9981 is 254 until it becomes 255 and never fires at all.
        val quantised = WakeWordSensitivity.entries.map {
            (it.probabilityCutoff * 255).toInt() to it.slidingWindowSize
        }

        assertEquals(quantised.size, quantised.distinct().size)
        assertTrue("a cutoff quantising to 255 can never be exceeded", quantised.all { it.first < 255 })
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
