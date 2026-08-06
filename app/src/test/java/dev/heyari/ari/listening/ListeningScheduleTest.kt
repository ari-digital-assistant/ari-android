package dev.heyari.ari.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Schedule window arithmetic. Midnight-crossing is the whole difficulty here:
 * "Friday 22:00 to 06:00" is in force at 01:00 on SATURDAY, on the strength of
 * FRIDAY being ticked.
 */
class ListeningScheduleTest {

    private fun schedule(
        days: Set<DayOfWeek>,
        start: String,
        end: String,
        id: String = "s1",
    ) = ListeningSchedule(
        id = id,
        days = days,
        startMinute = start.toMinuteOfDay(),
        endMinute = end.toMinuteOfDay(),
    )

    private fun String.toMinuteOfDay(): Int {
        val (h, m) = split(":").map(String::toInt)
        return h * 60 + m
    }

    // 2026-08-06 is a Thursday; 2026-08-07 a Friday, 2026-08-08 a Saturday.
    private fun at(day: Int, time: String): LocalDateTime {
        val (h, m) = time.split(":").map(String::toInt)
        return LocalDateTime.of(2026, 8, day, h, m)
    }

    @Test
    fun `inside a plain daytime window`() {
        val s = schedule(setOf(DayOfWeek.THURSDAY), "09:00", "17:00")
        assertTrue(s.contains(at(6, "09:00")))
        assertTrue(s.contains(at(6, "12:30")))
        assertTrue(s.contains(at(6, "16:59")))
    }

    @Test
    fun `the window is start-inclusive and end-exclusive`() {
        val s = schedule(setOf(DayOfWeek.THURSDAY), "09:00", "17:00")
        assertFalse(s.contains(at(6, "08:59")))
        assertTrue(s.contains(at(6, "09:00")))
        assertFalse(s.contains(at(6, "17:00")))
    }

    @Test
    fun `the wrong weekday is outside`() {
        val s = schedule(setOf(DayOfWeek.THURSDAY), "09:00", "17:00")
        assertFalse(s.contains(at(7, "12:00")))
    }

    @Test
    fun `a window crossing midnight holds on both sides of it`() {
        val s = schedule(setOf(DayOfWeek.FRIDAY), "22:00", "06:00")
        assertFalse(s.contains(at(7, "21:59")))
        assertTrue(s.contains(at(7, "22:00")))
        assertTrue(s.contains(at(7, "23:59")))
        assertTrue(s.contains(at(8, "00:00")))
        assertTrue(s.contains(at(8, "05:59")))
        assertFalse(s.contains(at(8, "06:00")))
    }

    @Test
    fun `a midnight-crossing window does not leak into the morning of its own day`() {
        // Friday 22:00-06:00 must NOT be live at 01:00 on Friday — that tail
        // belongs to a Thursday window, and Thursday isn't ticked.
        val s = schedule(setOf(DayOfWeek.FRIDAY), "22:00", "06:00")
        assertFalse(s.contains(at(7, "01:00")))
    }

    @Test
    fun `no days ticked is never in force`() {
        val s = schedule(emptySet(), "09:00", "17:00")
        assertFalse(s.contains(at(6, "12:00")))
    }

    @Test
    fun `any of several schedules is enough`() {
        val schedules = listOf(
            schedule(setOf(DayOfWeek.MONDAY), "09:00", "17:00", id = "weekday"),
            schedule(setOf(DayOfWeek.THURSDAY), "18:00", "20:00", id = "evening"),
        )
        assertTrue(isWithinAnySchedule(at(6, "19:00"), schedules))
        assertFalse(isWithinAnySchedule(at(6, "17:30"), schedules))
    }

    @Test
    fun `no schedules is never in force`() {
        assertFalse(isWithinAnySchedule(at(6, "12:00"), emptyList()))
    }

    @Test
    fun `next boundary before the window opens is the opening`() {
        val s = listOf(schedule(setOf(DayOfWeek.THURSDAY), "09:00", "17:00"))
        assertEquals(at(6, "09:00"), nextBoundaryAfter(at(6, "07:00"), s))
    }

    @Test
    fun `next boundary inside the window is the closing`() {
        val s = listOf(schedule(setOf(DayOfWeek.THURSDAY), "09:00", "17:00"))
        assertEquals(at(6, "17:00"), nextBoundaryAfter(at(6, "12:00"), s))
    }

    @Test
    fun `next boundary after today's window rolls to next week`() {
        val s = listOf(schedule(setOf(DayOfWeek.THURSDAY), "09:00", "17:00"))
        assertEquals(at(13, "09:00"), nextBoundaryAfter(at(6, "18:00"), s))
    }

    @Test
    fun `next boundary inside a midnight-crossing tail is that tail's close`() {
        // 01:00 Saturday, inside Friday's 22:00-06:00 window. The boundary is
        // 06:00 Saturday — generated from FRIDAY's date, which is why the
        // search span has to start a day before today.
        val s = listOf(schedule(setOf(DayOfWeek.FRIDAY), "22:00", "06:00"))
        assertEquals(at(8, "06:00"), nextBoundaryAfter(at(8, "01:00"), s))
    }

    @Test
    fun `next boundary picks the earliest across overlapping schedules`() {
        val s = listOf(
            schedule(setOf(DayOfWeek.THURSDAY), "09:00", "17:00", id = "day"),
            schedule(setOf(DayOfWeek.THURSDAY), "10:00", "11:00", id = "meeting"),
        )
        assertEquals(at(6, "10:00"), nextBoundaryAfter(at(6, "09:30"), s))
    }

    @Test
    fun `no schedules has no next boundary`() {
        assertNull(nextBoundaryAfter(at(6, "12:00"), emptyList()))
    }

    @Test
    fun `a schedule with no days has no next boundary`() {
        assertNull(nextBoundaryAfter(at(6, "12:00"), listOf(schedule(emptySet(), "09:00", "17:00"))))
    }

    @Test
    fun `schedules survive a JSON round trip`() {
        val original = listOf(
            schedule(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY), "07:30", "09:15", id = "a"),
            schedule(setOf(DayOfWeek.FRIDAY), "22:00", "06:00", id = "b"),
        )
        assertEquals(original, decodeSchedules(encodeSchedules(original)))
    }

    @Test
    fun `an empty or corrupt store decodes to nothing`() {
        assertEquals(emptyList<ListeningSchedule>(), decodeSchedules(null))
        assertEquals(emptyList<ListeningSchedule>(), decodeSchedules(""))
        assertEquals(emptyList<ListeningSchedule>(), decodeSchedules("not json"))
    }

    @Test
    fun `entries without an id are dropped, not defaulted`() {
        val raw = """[{"days":[1],"start":540,"end":1020}]"""
        assertEquals(emptyList<ListeningSchedule>(), decodeSchedules(raw))
    }

    @Test
    fun `out-of-range day numbers are dropped without losing the schedule`() {
        val raw = """[{"id":"a","days":[1,9,3],"start":540,"end":1020}]"""
        assertEquals(
            listOf(
                ListeningSchedule(
                    id = "a",
                    days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                    startMinute = 540,
                    endMinute = 1020,
                )
            ),
            decodeSchedules(raw),
        )
    }
}
