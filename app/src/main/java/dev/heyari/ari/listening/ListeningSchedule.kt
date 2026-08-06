package dev.heyari.ari.listening

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One recurring listening window: a set of weekdays and a time range.
 *
 * Times are minutes from midnight rather than a `LocalTime` so the stored form
 * is a plain integer and the arithmetic below has no timezone opinions — the
 * window is wall-clock local, which is what a user picking "09:00" means
 * wherever they happen to be standing.
 *
 * A window whose end is not after its start crosses midnight and belongs to the
 * day its START falls on. "Friday 22:00 to 06:00" is Friday night, not Friday
 * morning, and that is how people say it. An equal start and end is therefore a
 * full 24 hours; the editor refuses to create one.
 */
data class ListeningSchedule(
    val id: String,
    val days: Set<DayOfWeek>,
    val startMinute: Int,
    val endMinute: Int,
) {
    val crossesMidnight: Boolean get() = endMinute <= startMinute

    /** True if [now]'s wall clock and weekday fall inside this window. */
    fun contains(now: LocalDateTime): Boolean {
        if (days.isEmpty()) return false
        val minute = now.hour * 60 + now.minute
        val today = now.dayOfWeek

        if (today in days) {
            val started = minute >= startMinute
            if (started && (crossesMidnight || minute < endMinute)) return true
        }
        // The tail of a window that began yesterday and ran through midnight.
        if (crossesMidnight && today.minus(1) in days && minute < endMinute) return true
        return false
    }

    /**
     * The window's two boundary instants for a window STARTING on [date], or
     * nothing if the window doesn't run that day.
     */
    internal fun boundariesStartingOn(date: LocalDate): List<LocalDateTime> {
        if (date.dayOfWeek !in days) return emptyList()
        val start = date.atStartOfDay().plusMinutes(startMinute.toLong())
        val end = if (crossesMidnight) {
            date.plusDays(1).atStartOfDay().plusMinutes(endMinute.toLong())
        } else {
            date.atStartOfDay().plusMinutes(endMinute.toLong())
        }
        return listOf(start, end)
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

internal fun isWithinAnySchedule(now: LocalDateTime, schedules: List<ListeningSchedule>): Boolean =
    schedules.any { it.contains(now) }

/**
 * The next instant at which [isWithinAnySchedule] could change, or null if no
 * schedule ever fires again (i.e. there are none, or none has any day set).
 *
 * Enumerating the boundaries over a nine-day span and taking the earliest one
 * after [now] beats closed-form arithmetic here: it is obviously correct at a
 * glance, it handles midnight-crossing and overlapping windows without a single
 * special case, and the list it walks is at most a few dozen entries. The span
 * starts YESTERDAY so the tail boundary of a window that ran through last
 * midnight isn't missed, and runs a full week past today so a single Monday-only
 * schedule still resolves on a Tuesday.
 */
internal fun nextBoundaryAfter(
    now: LocalDateTime,
    schedules: List<ListeningSchedule>,
): LocalDateTime? {
    if (schedules.isEmpty()) return null
    val today = now.toLocalDate()
    return (-1L..8L)
        .map(today::plusDays)
        .flatMap { date -> schedules.flatMap { it.boundariesStartingOn(date) } }
        .filter { it.isAfter(now) }
        .minOrNull()
}

internal fun encodeSchedules(schedules: List<ListeningSchedule>): String {
    val arr = JSONArray()
    schedules.forEach { schedule ->
        arr.put(
            JSONObject().apply {
                put("id", schedule.id)
                // ISO day numbers (Mon=1 … Sun=7), not enum names or locale-
                // dependent labels — the stored form has to survive a language
                // change and an enum rename.
                put("days", JSONArray().apply { schedule.days.sorted().forEach { put(it.value) } })
                put("start", schedule.startMinute)
                put("end", schedule.endMinute)
            }
        )
    }
    return arr.toString()
}

internal fun decodeSchedules(raw: String?): List<ListeningSchedule> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val dayArr = obj.optJSONArray("days") ?: JSONArray()
            val days = (0 until dayArr.length())
                .mapNotNull { d -> runCatching { DayOfWeek.of(dayArr.getInt(d)) }.getOrNull() }
                .toSet()
            ListeningSchedule(
                id = id,
                days = days,
                startMinute = obj.optInt("start").coerceIn(0, ListeningSchedule.MINUTES_PER_DAY - 1),
                endMinute = obj.optInt("end").coerceIn(0, ListeningSchedule.MINUTES_PER_DAY - 1),
            )
        }
    } catch (e: JSONException) {
        // Losing every schedule silently would look like the feature is broken
        // rather than like the stored blob is. Start clean, but say so.
        Log.w(TAG, "Corrupt schedule store — dropping all schedules", e)
        emptyList()
    }
}

private const val TAG = "ListeningSchedule"
