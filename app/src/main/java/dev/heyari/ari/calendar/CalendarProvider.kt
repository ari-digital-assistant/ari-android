package dev.heyari.ari.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around Android's `CalendarContract` — the standard
 * cross-app calendar API. Two responsibilities:
 *
 * 1. Enumerating the user's writable calendars (for picker UI and
 *    skills that want to show a destination chooser).
 * 2. Inserting and deleting VEVENT rows, each paired with a pop-up
 *    reminder row so the event actually notifies.
 *
 * This is a **platform wrapper**, not a skill feature. Any skill
 * declaring `Capability::Calendar` can reach it via the
 * `ari::calendar_*` host imports; this Kotlin surface is also used
 * by generic UI (the `device_calendar` picker in
 * `SkillSettingsPanel`).
 *
 * Permission gating lives at the call sites. Methods here return
 * null / empty / false on permission denial so the caller can
 * render a sensible empty state instead of crashing.
 */
@Singleton
class CalendarProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * One row from `CalendarContract.Calendars` reduced to what the
     * picker needs. `accountName` is the email / handle the user
     * associated the calendar with — useful for disambiguating
     * "Personal" calendars sourced from different accounts.
     */
    data class DeviceCalendar(
        val id: Long,
        val displayName: String,
        val accountName: String,
        val isPrimary: Boolean,
        val colorArgb: Int?,
    )

    fun hasReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Every calendar the user has write access to. Sorted by
     * isPrimary (primary first) then by display name. Returns empty
     * if the READ_CALENDAR permission isn't granted yet — no throw,
     * caller decides whether to prompt.
     *
     * Filters to writable calendars only — read-only mounts (subscribed
     * holiday calendars, shared read-only colleagues' diaries) would
     * fail at insert time with a confusing error if we let the user
     * pick one as their default.
     */
    fun listCalendars(): List<DeviceCalendar> {
        if (!hasReadPermission()) return emptyList()

        val out = mutableListOf<DeviceCalendar>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        // CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_CONTRIBUTOR (500) is the
        // documented "can insert events" threshold. Constants: CONTRIBUTOR
        // = 500, EDITOR = 600, OWNER = 700.
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(
            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString(),
        )

        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null,
            )
        }
            .onFailure { e ->
                Log.w(TAG, "calendar query failed: ${e.message}")
            }
            .getOrNull()
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val displayName = cursor.getString(1) ?: "(unnamed)"
                    val accountName = cursor.getString(2) ?: ""
                    // IS_PRIMARY is "1"/"0" or null on some devices; treat
                    // null as "not primary" so we don't accidentally promote
                    // every calendar.
                    val isPrimary = (cursor.getString(3) ?: "0") == "1"
                    val colorArgb = if (!cursor.isNull(4)) cursor.getInt(4) else null
                    out.add(DeviceCalendar(id, displayName, accountName, isPrimary, colorArgb))
                }
            }

        return out.sortedWith(
            compareByDescending<DeviceCalendar> { it.isPrimary }.thenBy { it.displayName },
        )
    }

    /**
     * The calendar the system considers primary, or the first writable
     * calendar if no row reports IS_PRIMARY. Used as the default when
     * the user hasn't explicitly chosen one. Null only if the device
     * has no writable calendars at all (shouldn't happen on stock
     * Android — there's always at least the local account).
     */
    fun primaryCalendar(): DeviceCalendar? {
        val all = listCalendars()
        return all.firstOrNull { it.isPrimary } ?: all.firstOrNull()
    }

    /**
     * Insert a VEVENT into [calendarId] starting at [startMillis] and
     * lasting [durationMinutes]. Adds a single pop-up reminder
     * [reminderMinutesBefore] minutes before so the event actually
     * notifies — bare events with no reminders are useless for the
     * "remind me" use case.
     *
     * Returns the inserted event's row id, or null on failure (most
     * commonly: WRITE_CALENDAR not granted, or the calendar id no
     * longer exists). Failures are logged but not thrown — callers
     * usually want to fall back to a Tasks insert or a "not stored"
     * message rather than crash.
     */
    fun insertEvent(
        calendarId: Long,
        title: String,
        startMillis: Long,
        durationMinutes: Int = DEFAULT_EVENT_DURATION_MINUTES,
        reminderMinutesBefore: Int = DEFAULT_REMINDER_MINUTES_BEFORE,
        tzId: String = java.util.TimeZone.getDefault().id,
    ): Long? {
        if (!hasWritePermission()) {
            Log.w(TAG, "insertEvent: WRITE_CALENDAR not granted")
            return null
        }

        val endMillis = startMillis + (durationMinutes.toLong() * 60_000L)
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, tzId)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }

        val eventUri = runCatching {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }
            .onFailure { e ->
                Log.w(TAG, "event insert failed: ${e.message}")
            }
            .getOrNull()
            ?: return null

        val eventId = ContentUris.parseId(eventUri)

        // Reminder row — the calendar provider treats reminders as
        // separate rows joined by EVENT_ID. Without this row the event
        // exists but the user gets no notification.
        if (reminderMinutesBefore > 0) {
            val reminderValues = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, reminderMinutesBefore)
                put(
                    CalendarContract.Reminders.METHOD,
                    CalendarContract.Reminders.METHOD_ALERT,
                )
            }
            runCatching {
                context.contentResolver.insert(
                    CalendarContract.Reminders.CONTENT_URI,
                    reminderValues,
                )
            }.onFailure { e ->
                Log.w(TAG, "reminder insert failed: ${e.message}")
            }
        }

        return eventId
    }

    /**
     * One event instance from a [queryEventsInRange] lookup.
     * Recurring events expand into multiple rows — one per concrete
     * instance whose start lands in the queried window — matching
     * the way `CalendarContract.Instances` works.
     */
    data class DeviceEventRow(
        val id: Long,
        val title: String,
        val startMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
        val calendarId: Long,
    )

    /**
     * Event instances starting in `[startMillis, endMillis)`,
     * ordered by start ascending and capped at [limit]. Goes through
     * `CalendarContract.Instances` (rather than `Events`) so
     * recurring events expand correctly — one row per occurrence in
     * the window. Empty list if READ_CALENDAR isn't granted or the
     * range is empty.
     */
    fun queryEventsInRange(
        startMillis: Long,
        endMillis: Long,
        limit: Int,
    ): List<DeviceEventRow> {
        if (limit <= 0 || endMillis <= startMillis) return emptyList()
        if (!hasReadPermission()) return emptyList()

        // Instances queries take the start/end as path segments
        // appended to the base URI rather than as selection args —
        // CalendarContract reads the window from the URI itself.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMillis.toString())
            .appendPath(endMillis.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
        )
        val sortOrder = "${CalendarContract.Instances.BEGIN} ASC"

        val out = mutableListOf<DeviceEventRow>()
        runCatching {
            context.contentResolver.query(uri, projection, null, null, sortOrder)
        }
            .onFailure { e -> Log.w(TAG, "event range query failed: ${e.message}") }
            .getOrNull()
            ?.use { cursor ->
                while (cursor.moveToNext() && out.size < limit) {
                    val id = cursor.getLong(0)
                    val title = cursor.getString(1) ?: continue
                    val begin = cursor.getLong(2)
                    val end = cursor.getLong(3)
                    val allDay = cursor.getInt(4) == 1
                    val calId = cursor.getLong(5)
                    out.add(DeviceEventRow(id, title, begin, end, allDay, calId))
                }
            }
        return out
    }

    /**
     * Delete an event by id. CalendarContract cascades to the paired
     * Reminder rows automatically. Returns true if the row existed
     * and was removed.
     */
    fun deleteEvent(eventId: Long): Boolean {
        if (!hasWritePermission()) {
            Log.w(TAG, "deleteEvent: WRITE_CALENDAR not granted")
            return false
        }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val deleted = runCatching { context.contentResolver.delete(uri, null, null) }
            .onFailure { Log.w(TAG, "event delete failed for id=$eventId: ${it.message}") }
            .getOrNull()
            ?: return false
        return deleted > 0
    }

    companion object {
        private const val TAG = "CalendarProvider"
        private const val DEFAULT_EVENT_DURATION_MINUTES = 30
        private const val DEFAULT_REMINDER_MINUTES_BEFORE = 5
    }
}
