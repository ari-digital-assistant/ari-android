package dev.heyari.ari.voice

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
