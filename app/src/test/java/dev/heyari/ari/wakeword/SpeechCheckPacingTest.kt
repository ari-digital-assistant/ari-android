package dev.heyari.ari.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCheckPacingTest {

    @Test
    fun `the first report of an episode is checked at once`() {
        assertTrue(SpeechCheckPacing().shouldCheck(10_000L))
    }

    @Test
    fun `after a drop the check waits 250 ms while the engine keeps reporting`() {
        val pacing = SpeechCheckPacing()
        pacing.shouldCheck(10_000L)
        pacing.dropped(10_000L)

        // The engine reports every 20 ms on Hey Ari.
        val checked = (10_020L..10_400L step 20).filter { pacing.shouldCheck(it) }

        assertEquals(listOf(10_260L), checked.take(1))
    }

    @Test
    fun `one episode logs one drop however many checks it takes`() {
        val pacing = SpeechCheckPacing()
        val logged = mutableListOf<Long>()
        for (t in 10_000L..11_000L step 20) {
            if (pacing.shouldCheck(t) && pacing.dropped(t)) logged += t
        }

        assertEquals(listOf(10_000L), logged)
    }

    @Test
    fun `a pass after a logged drop in the same episode says the drop prevented nothing`() {
        val pacing = SpeechCheckPacing()
        pacing.shouldCheck(10_000L)
        assertFalse(pacing.passed())

        pacing.dropped(10_000L)
        pacing.shouldCheck(10_260L)

        assertTrue(pacing.passed())
    }

    @Test
    fun `a pass in a fresh episode is a plain wake`() {
        val pacing = SpeechCheckPacing()
        pacing.shouldCheck(10_000L)
        pacing.dropped(10_000L)
        pacing.shouldCheck(12_000L)

        assertFalse(pacing.passed())
    }

    @Test
    fun `a gap of over a second starts a new episode, checked and logged afresh`() {
        val pacing = SpeechCheckPacing()
        pacing.shouldCheck(10_000L)
        pacing.dropped(10_000L)

        assertFalse(pacing.shouldCheck(10_100L))
        // The engine fell quiet, then reported again 1.2 s later.
        assertTrue(pacing.shouldCheck(11_300L))
        assertTrue(pacing.dropped(11_300L))
    }
}
