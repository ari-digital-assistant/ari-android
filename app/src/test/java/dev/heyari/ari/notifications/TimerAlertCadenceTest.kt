package dev.heyari.ari.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerAlertCadenceTest {

    @Test
    fun continuesBelowBothCaps() {
        assertTrue(shouldContinueAlerting(cyclesCompleted = 0, elapsedMs = 0))
        assertTrue(shouldContinueAlerting(cyclesCompleted = 5, elapsedMs = 10_000))
        assertTrue(shouldContinueAlerting(cyclesCompleted = 11, elapsedMs = 119_999))
    }

    @Test
    fun stopsAtCycleCap() {
        // Exact-boundary case: `cyclesCompleted = maxCycles` means we've
        // done exactly the cap and should stop. Off-by-one here would either
        // ring 13 times or cut it to 11.
        assertFalse(shouldContinueAlerting(cyclesCompleted = 12, elapsedMs = 5_000))
    }

    @Test
    fun stopsAtDurationCap() {
        assertFalse(shouldContinueAlerting(cyclesCompleted = 3, elapsedMs = 120_000))
        assertFalse(shouldContinueAlerting(cyclesCompleted = 3, elapsedMs = 999_999))
    }

    @Test
    fun stopsWhenEitherCapMet() {
        // Duration wins: under the cycle cap but over the time cap.
        assertFalse(shouldContinueAlerting(cyclesCompleted = 2, elapsedMs = 150_000))
        // Cycles wins: under the time cap but over the cycle cap.
        assertFalse(shouldContinueAlerting(cyclesCompleted = 20, elapsedMs = 30_000))
    }

    @Test
    fun customCapsRespected() {
        assertTrue(shouldContinueAlerting(1, 1_000, maxCycles = 3, maxDurationMs = 5_000))
        assertFalse(shouldContinueAlerting(3, 1_000, maxCycles = 3, maxDurationMs = 5_000))
        assertFalse(shouldContinueAlerting(1, 5_000, maxCycles = 3, maxDurationMs = 5_000))
    }
}
