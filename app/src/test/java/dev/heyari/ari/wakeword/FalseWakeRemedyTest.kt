package dev.heyari.ari.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FalseWakeRemedyTest {

    private val model = WakeWordRegistry.byId("hey_ari")

    @Test
    fun `the ladder is declared strictest-last, which is what stepping down relies on`() {
        // forLevel walks this list rather than comparing cutoffs, because the
        // numbers behind each rung differ per model and cannot be compared
        // across one. Inverting this list silently inverts the remedy.
        assertEquals(
            listOf(WakeWordSensitivity.HIGH, WakeWordSensitivity.MEDIUM, WakeWordSensitivity.LOW),
            WakeWordSensitivity.loosestFirst,
        )
    }

    @Test
    fun `the loose settings just step down the ladder they already have`() {
        assertEquals(
            FalseWakeRemedy.StepDown(WakeWordSensitivity.MEDIUM),
            FalseWakeRemedy.forLevel(WakeWordSensitivity.HIGH, model),
        )
        assertEquals(
            FalseWakeRemedy.StepDown(WakeWordSensitivity.LOW),
            FalseWakeRemedy.forLevel(WakeWordSensitivity.MEDIUM, model),
        )
    }

    @Test
    fun `on the strictest setting the ladder itself moves`() {
        val remedy = FalseWakeRemedy.forLevel(WakeWordSensitivity.LOW, model)

        val tighten = remedy as FalseWakeRemedy.Tighten
        assertEquals(
            model.low.probabilityCutoff + FalseWakeRemedy.TIGHTEN_STEP,
            tighten.ladder.medium,
            0.0001f,
        )
    }

    @Test
    fun `tightening from the strictest setting really is stricter`() {
        // The trap in the obvious implementation: shift the ladder up a step
        // and then land the user on its MIDDLE, which is below the rung they
        // were already on. They report false wakes and get a looser phone.
        val before = model.operatingPoint(WakeWordSensitivity.LOW).probabilityCutoff

        val remedy = FalseWakeRemedy.forLevel(WakeWordSensitivity.LOW, model)

        val after = (remedy as FalseWakeRemedy.Tighten).ladder.medium
        assertTrue("$after should be stricter than $before", after > before)
    }

    @Test
    fun `repeated tightening stops rather than pretending`() {
        // Walk it up the way a household would: complain, accept, complain.
        var current = model
        repeat(8) {
            when (val remedy = FalseWakeRemedy.forLevel(WakeWordSensitivity.MEDIUM, current)) {
                is FalseWakeRemedy.Tighten -> current = current.withLadder(remedy.ladder)
                is FalseWakeRemedy.StepDown -> current = current.withLadder(
                    TunedLadder.centredOn(
                        current.id,
                        current.operatingPoint(remedy.to).probabilityCutoff,
                        current.medium.slidingWindowSize,
                    )
                )
                FalseWakeRemedy.Exhausted -> return@repeat
            }
            assertTrue(
                "never past the point where cutoffs stop being distinguishable",
                current.low.probabilityCutoff <= TunedLadder.MAX_CUTOFF,
            )
        }
    }

    @Test
    fun `a phone already at the ceiling is told so instead of being nudged`() {
        val pinned = model.withLadder(
            TunedLadder("hey_ari", 0.90f, 0.93f, TunedLadder.MAX_CUTOFF, 10)
        )

        assertEquals(
            FalseWakeRemedy.Exhausted,
            FalseWakeRemedy.forLevel(WakeWordSensitivity.LOW, pinned),
        )
    }
}
