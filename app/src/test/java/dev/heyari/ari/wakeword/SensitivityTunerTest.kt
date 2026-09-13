package dev.heyari.ari.wakeword

import dev.heyari.ari.wakeword.SensitivityTuner.Companion.pickLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensitivityTunerTest {

    private fun speaker(name: String, high: Int, medium: Int, low: Int) = TunedSpeaker(
        name = name,
        attempts = SensitivityTuner.ATTEMPTS,
        heardAt = mapOf(
            WakeWordSensitivity.HIGH to high,
            WakeWordSensitivity.MEDIUM to medium,
            WakeWordSensitivity.LOW to low,
        ),
    )

    @Test
    fun `a speaker heard everywhere gets the strictest level`() {
        // No reason to make the phone twitchier than it has to be.
        assertEquals(
            WakeWordSensitivity.LOW,
            pickLevel(listOf(speaker("Keith", high = 5, medium = 5, low = 5))),
        )
    }

    @Test
    fun `the level has to clear the bar for everybody, not the average`() {
        // Exactly the failure this screen exists to prevent: measure only the
        // person holding the phone and LOW looks right, with his wife ignored.
        val speakers = listOf(
            speaker("Keith", high = 5, medium = 5, low = 5),
            speaker("Gail", high = 4, medium = 2, low = 1),
        )

        assertEquals(WakeWordSensitivity.HIGH, pickLevel(speakers))
    }

    @Test
    fun `one person short of the bar at every level yields no answer`() {
        val speakers = listOf(
            speaker("Keith", high = 5, medium = 5, low = 5),
            speaker("Joe", high = 3, medium = 3, low = 2),
        )

        // The caller falls back to HIGH and says so rather than showing a tick.
        assertNull(pickLevel(speakers))
    }

    @Test
    fun `exactly the required number of hits is good enough`() {
        assertEquals(
            WakeWordSensitivity.MEDIUM,
            pickLevel(
                listOf(
                    speaker("Gail", high = 5, medium = SensitivityTuner.REQUIRED_HITS, low = 3),
                ),
            ),
        )
    }

    @Test
    fun `nobody enrolled is not an answer of LOW`() {
        // vacuously "every speaker cleared it" would hand back the strictest
        // setting on the strength of no evidence whatsoever.
        assertNull(pickLevel(emptyList()))
    }

    @Test
    fun `strictness is read off the ladder rather than its declaration order`() {
        // pickLevel ranks by cutoff * window because that is what the engine
        // compares against; a level reordered in the enum must not change which
        // one is considered strictest.
        val byStrictness = WakeWordSensitivity.entries
            .sortedByDescending { it.probabilityCutoff * it.slidingWindowSize }

        assertEquals(
            listOf(WakeWordSensitivity.LOW, WakeWordSensitivity.MEDIUM, WakeWordSensitivity.HIGH),
            byStrictness,
        )
    }
}
