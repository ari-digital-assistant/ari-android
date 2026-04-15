package dev.heyari.ari.reminders

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
 * Thin wrapper around `CalendarContract` for the reminder skill's
 * Calendar / Both destination modes. Two responsibilities:
 *
 * 1. Enumerating the user's writable calendars so the
 *    `device_calendar` setting picker can render them.
 * 2. Inserting a VEVENT row with a 5-minute pop-up alarm into the
 *    user's chosen calendar (or the primary if no choice was made).
 *
 * Permission gating lives at the call sites — the picker composable
 * triggers the runtime grant before invoking [listCalendars], and the
 * action handler does the same before [insertEvent]. Methods here
 * return null / empty on permission denial so the caller can render a
 * sensible empty state instead of crashing.
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
                    out.add(DeviceCalendar(id, displayName, accountName, isPrimary))
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
     * lasting [durationMinutes]. Adds a single 5-minute-before pop-up
     * reminder so the event actually notifies the user — bare events
     * with no reminders are useless for the "remind me" use case.
     *
     * Returns the inserted event's row id, or null on failure (most
     * commonly: WRITE_CALENDAR not granted, or the calendar id no
     * longer exists). Failures are logged but not thrown — the caller
     * usually wants to fall back to a Tasks insert rather than crash.
     */
    fun insertEvent(
        calendarId: Long,
        title: String,
        startMillis: Long,
        durationMinutes: Int = DEFAULT_EVENT_DURATION_MINUTES,
        reminderMinutesBefore: Int = DEFAULT_REMINDER_MINUTES_BEFORE,
    ): Long? {
        if (!hasWritePermission()) {
            Log.w(TAG, "insertEvent: WRITE_CALENDAR not granted")
            return null
        }

        val endMillis = startMillis + (durationMinutes.toLong() * 60_000L)
        // EVENT_TIMEZONE is required by the calendar provider — the
        // device's default zone is what we want since the skill emits
        // local-clock descriptors that the action handler resolved
        // against the same zone before getting here.
        val tzId = java.util.TimeZone.getDefault().id
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

        return eventId
    }

    companion object {
        private const val TAG = "CalendarProvider"
        private const val DEFAULT_EVENT_DURATION_MINUTES = 30
        private const val DEFAULT_REMINDER_MINUTES_BEFORE = 5
    }
}
