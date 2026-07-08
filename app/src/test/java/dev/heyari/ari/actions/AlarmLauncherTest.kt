package dev.heyari.ari.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmLauncherTest {
    @Test
    fun maps_day_codes_to_calendar_constants() {
        // java.util.Calendar: SUNDAY=1, MONDAY=2 … SATURDAY=7
        assertEquals(2, AlarmLauncher.dayCodeToCalendar("mon"))
        assertEquals(3, AlarmLauncher.dayCodeToCalendar("tue"))
        assertEquals(4, AlarmLauncher.dayCodeToCalendar("wed"))
        assertEquals(5, AlarmLauncher.dayCodeToCalendar("thu"))
        assertEquals(6, AlarmLauncher.dayCodeToCalendar("fri"))
        assertEquals(7, AlarmLauncher.dayCodeToCalendar("sat"))
        assertEquals(1, AlarmLauncher.dayCodeToCalendar("sun"))
    }

    @Test
    fun unknown_day_code_is_null() {
        assertNull(AlarmLauncher.dayCodeToCalendar("funday"))
    }
}
