package dev.heyari.ari.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunedLadderTest {

    private val model = WakeWordRegistry.byId("hey_ari")

    @Test
    fun `a ladder is spaced a step either side of its middle`() {
        val ladder = TunedLadder.centredOn("hey_ari", 0.70f, 10)

        assertEquals(0.60f, ladder.high, TOLERANCE)
        assertEquals(0.70f, ladder.medium, TOLERANCE)
        assertEquals(0.80f, ladder.low, TOLERANCE)
    }

    @Test
    fun `near the ceiling the rungs compress rather than dragging the middle down`() {
        // The remedy path asks for a middle of 0.90 when somebody on the
        // strictest setting is still getting false wakes. Answering 0.85
        // because 0.90 + a step will not fit would quietly hand back a LOOSER
        // setting than the one they complained about.
        val ladder = TunedLadder.centredOn("hey_ari", 0.90f, 10)

        assertEquals(0.90f, ladder.medium, TOLERANCE)
        assertEquals(0.80f, ladder.high, TOLERANCE)
        assertEquals(TunedLadder.MAX_CUTOFF, ladder.low, TOLERANCE)
        assertTrue("rungs must stay distinct", ladder.medium < ladder.low)
    }

    @Test
    fun `the rungs stay ordered and distinct at both limits`() {
        listOf(0f, 0.1f, 0.5f, 0.94f, 0.99f, 1f).forEach { asked ->
            val ladder = TunedLadder.centredOn("hey_ari", asked, 10)
            assertTrue("$asked: high < medium", ladder.high < ladder.medium)
            assertTrue("$asked: medium < low", ladder.medium < ladder.low)
            assertTrue("$asked: within bounds", ladder.high >= TunedLadder.MIN_CUTOFF)
            assertTrue("$asked: within bounds", ladder.low <= TunedLadder.MAX_CUTOFF)
        }
    }

    @Test
    fun `a measured ladder may tighten the built-in one`() {
        val ladder = TunedLadder.centredOn("hey_ari", 0.90f, 10).atLeast(model)

        assertTrue(ladder.medium > model.medium.probabilityCutoff)
    }

    @Test
    fun `a measured ladder may never loosen the built-in one`() {
        // The guard rail. A session recorded in a silent hallway produces no
        // decoys worth clearing and would otherwise solve for a very low
        // cutoff — which is precisely the ladder that shipped in September and
        // false-fired within two days.
        val ladder = TunedLadder.centredOn("hey_ari", 0.35f, 10).atLeast(model)

        assertEquals(model.high.probabilityCutoff, ladder.high, TOLERANCE)
        assertEquals(model.medium.probabilityCutoff, ladder.medium, TOLERANCE)
        assertEquals(model.low.probabilityCutoff, ladder.low, TOLERANCE)
    }

    @Test
    fun `a ladder survives being written down and read back`() {
        val ladder = TunedLadder.centredOn("hey_ari", 0.72f, 10)

        assertEquals(ladder, TunedLadder.parse(ladder.format()))
    }

    @Test
    fun `nonsense on the way back in is refused rather than half applied`() {
        assertNull(TunedLadder.parse(null))
        assertNull(TunedLadder.parse(""))
        assertNull(TunedLadder.parse("hey_ari|0.6|0.7"))
        assertNull(TunedLadder.parse("hey_ari|0.6|0.7|0.8|nope"))
        // Out of order: LOW must be the strictest or the whole picker inverts.
        assertNull(TunedLadder.parse("hey_ari|0.9|0.7|0.5|10"))
        // Equal rungs quantise to the same byte, so three settings do one thing.
        assertNull(TunedLadder.parse("hey_ari|0.7|0.7|0.8|10"))
        assertNull(TunedLadder.parse("hey_ari|0.1|0.2|0.3|10"))
    }

    @Test
    fun `a ladder belonging to another model is ignored, not applied`() {
        val ladder = TunedLadder.centredOn("ok_ari", 0.50f, 10)

        assertEquals(model, model.withLadder(ladder))
    }

    @Test
    fun `a ladder for this model replaces every rung and the window`() {
        val ladder = TunedLadder("hey_ari", 0.60f, 0.70f, 0.80f, 15)

        val tuned = model.withLadder(ladder)

        assertEquals(OperatingPoint(0.60f, 15), tuned.operatingPoint(WakeWordSensitivity.HIGH))
        assertEquals(OperatingPoint(0.70f, 15), tuned.operatingPoint(WakeWordSensitivity.MEDIUM))
        assertEquals(OperatingPoint(0.80f, 15), tuned.operatingPoint(WakeWordSensitivity.LOW))
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
