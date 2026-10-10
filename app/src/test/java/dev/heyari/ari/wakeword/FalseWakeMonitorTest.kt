package dev.heyari.ari.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FalseWakeMonitorTest {

    @Test
    fun `only false wakes inside the window count toward a burst`() {
        val now = 1_000_000_000L
        val timestamps = listOf(
            now - FalseWakeMonitor.WINDOW_MS - 1,
            now - FalseWakeMonitor.WINDOW_MS + 1,
            now - 1_000,
            now,
        )

        assertEquals(3, FalseWakeMonitor.prune(timestamps, now).size)
    }

    @Test
    fun `a clock that has gone backwards drops the future rather than counting it`() {
        // Timezone changes and NTP corrections both do this. A stamp from the
        // future would otherwise sit in the log forever, one short of a burst,
        // and tip the next real false wake into a notification early.
        val now = 1_000_000_000L

        assertEquals(emptyList<Long>(), FalseWakeMonitor.prune(listOf(now + 60_000), now))
    }

    @Test
    fun `five false wakes in the window are a burst, four are not`() {
        val four = listOf(1_000L, 2_000L, 3_000L, 4_000L)

        assertFalse(FalseWakeMonitor.burstDue(four, notifiedAt = 0L))
        assertTrue(FalseWakeMonitor.burstDue(four + 5_000L, notifiedAt = 0L))
    }

    @Test
    fun `after a warning only the false wakes since it count toward the next one`() {
        // The log keeps all six for the screen the warning opens; the next
        // warning still needs five new ones rather than arriving on the next.
        val recent = listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L)

        assertFalse(FalseWakeMonitor.burstDue(recent, notifiedAt = 5_000L))
        assertTrue(
            FalseWakeMonitor.burstDue(recent + listOf(7_000L, 8_000L, 9_000L, 10_000L), notifiedAt = 5_000L),
        )
    }
}
