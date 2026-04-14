package dev.heyari.ari.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertServiceCadenceTest {

    @Test
    fun continuesBelowBothCaps() {
        assertTrue(shouldContinueAlerting(0, 0, maxCycles = 12, maxDurationMs = 120_000))
        assertTrue(shouldContinueAlerting(5, 10_000, maxCycles = 12, maxDurationMs = 120_000))
        assertTrue(shouldContinueAlerting(11, 119_999, maxCycles = 12, maxDurationMs = 120_000))
    }

    @Test
    fun stopsAtCycleCap() {
        // exact-boundary case
        assertFalse(shouldContinueAlerting(12, 5_000, maxCycles = 12, maxDurationMs = 120_000))
    }

    @Test
    fun stopsAtDurationCap() {
        assertFalse(shouldContinueAlerting(3, 120_000, maxCycles = 12, maxDurationMs = 120_000))
    }

    @Test
    fun stopsWhenEitherCapMet() {
        // duration wins
        assertFalse(shouldContinueAlerting(2, 200_000, maxCycles = 12, maxDurationMs = 120_000))
        // cycles wins
        assertFalse(shouldContinueAlerting(20, 30_000, maxCycles = 12, maxDurationMs = 120_000))
    }

    @Test
    fun specDrivenCapsRespected() {
        // Caps are now per-AlertSpec rather than constants — this test
        // would catch a regression where someone hard-coded them again.
        assertTrue(shouldContinueAlerting(1, 1_000, maxCycles = 3, maxDurationMs = 5_000))
        assertFalse(shouldContinueAlerting(3, 1_000, maxCycles = 3, maxDurationMs = 5_000))
        assertFalse(shouldContinueAlerting(1, 5_000, maxCycles = 3, maxDurationMs = 5_000))
    }
}
