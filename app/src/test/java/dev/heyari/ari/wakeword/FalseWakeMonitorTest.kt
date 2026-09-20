package dev.heyari.ari.wakeword

import org.junit.Assert.assertEquals
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
}
