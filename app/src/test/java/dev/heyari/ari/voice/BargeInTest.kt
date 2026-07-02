package dev.heyari.ari.voice

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInTest {
    @Test fun bargeInEffective_requires_toggle_and_aec() {
        assertTrue(bargeInEffective(toggleEnabled = true, aecAvailable = true))
        assertFalse(bargeInEffective(toggleEnabled = true, aecAvailable = false))
        assertFalse(bargeInEffective(toggleEnabled = false, aecAvailable = true))
        assertFalse(bargeInEffective(toggleEnabled = false, aecAvailable = false))
    }

    @Test fun shouldCutTts_only_when_speaking_and_effective() {
        assertTrue(shouldCutTts(speaking = true, bargeInEffective = true))
        assertFalse(shouldCutTts(speaking = false, bargeInEffective = true))
        assertFalse(shouldCutTts(speaking = true, bargeInEffective = false))
    }

    @Test fun isStaleTurn_true_when_ids_differ() {
        assertTrue(isStaleTurn(finalTurnId = 3L, currentTurnId = 4L))
        assertFalse(isStaleTurn(finalTurnId = 4L, currentTurnId = 4L))
    }

    @Test fun captureMode_has_exactly_two_values() {
        assertEquals(2, CaptureMode.values().size)
    }

    @Test fun ownsSpeakingFlag_happy_path_current_job_owns_flag() {
        // Clean turn: the only ttsJob is the one finishing → it owns the flag
        // and its `finally` is allowed to clear speaking.
        val job = Job()
        assertTrue(ownsSpeakingFlag(currentJob = job, thisJob = job))
    }

    @Test fun ownsSpeakingFlag_superseded_job_does_not_own_flag() {
        // Barge: ttsJob is now N+1 while the cancelled job N's `finally` runs.
        // job N must NOT clear the flag its successor (N+1) owns.
        val jobN = Job()
        val jobNPlus1 = Job()
        assertFalse(ownsSpeakingFlag(currentJob = jobNPlus1, thisJob = jobN))
    }

    @Test fun ownsSpeakingFlag_false_when_current_job_null() {
        // dismiss() nulled ttsJob (it clears speaking directly): a straggler
        // `finally` must not resurrect ownership over a null current job.
        val job = Job()
        assertFalse(ownsSpeakingFlag(currentJob = null, thisJob = job))
        assertFalse(ownsSpeakingFlag(currentJob = null, thisJob = null))
    }

    @Test fun ownsSpeakingFlag_uses_identity_not_equality() {
        // Two distinct completed jobs compare unequal by identity even though
        // both are "completed" — no accidental collision.
        val a = Job()
        val b = Job()
        assertFalse(ownsSpeakingFlag(currentJob = a, thisJob = b))
        assertTrue(ownsSpeakingFlag(currentJob = a, thisJob = a))
    }

    @Test fun gate_is_conjunction_only() {
        // Exhaustive: effective iff BOTH inputs true.
        val table = listOf(
            Triple(true, true, true),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, false),
        )
        for ((toggle, aec, expected) in table) {
            assertEquals("$toggle,$aec", expected, bargeInEffective(toggle, aec))
        }
    }
}
