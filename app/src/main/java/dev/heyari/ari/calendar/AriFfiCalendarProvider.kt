package dev.heyari.ari.calendar

import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.FfiCalendar
import uniffi.ari_ffi.FfiCalendarEventRow
import uniffi.ari_ffi.FfiCalendarProvider
import uniffi.ari_ffi.FfiInsertCalendarEventParams

/**
 * Bridges the engine's foreign-callback [FfiCalendarProvider] trait
 * to the Android-native [CalendarProvider]. Any skill declaring
 * `Capability::Calendar` and calling `ari::calendar_*` from WASM
 * ends up here.
 */
@Singleton
class AriFfiCalendarProvider @Inject constructor(
    private val calendar: CalendarProvider,
) : FfiCalendarProvider {

    override fun hasWritePermission(): Boolean = calendar.hasWritePermission()

    override fun listCalendars(): List<FfiCalendar> = calendar.listCalendars().map {
        FfiCalendar(
            id = it.id.toULong(),
            displayName = it.displayName,
            accountName = it.accountName,
            colorArgb = it.colorArgb,
        )
    }

    override fun insert(params: FfiInsertCalendarEventParams): ULong {
        val rowId = calendar.insertEvent(
            calendarId = params.calendarId.toLong(),
            title = params.title,
            startMillis = params.startMs,
            durationMinutes = params.durationMinutes.toInt(),
            reminderMinutesBefore = params.reminderMinutesBefore.toInt(),
            tzId = params.tzId,
        )
        return rowId?.toULong() ?: 0UL
    }

    override fun delete(id: ULong): Boolean = calendar.deleteEvent(id.toLong())

    override fun queryInRange(
        startMs: Long,
        endMs: Long,
        limit: UInt,
    ): List<FfiCalendarEventRow> = calendar.queryEventsInRange(
        startMillis = startMs,
        endMillis = endMs,
        limit = limit.toInt(),
    ).map { row ->
        FfiCalendarEventRow(
            id = row.id.toULong(),
            title = row.title,
            startMs = row.startMillis,
            endMs = row.endMillis,
            allDay = row.allDay,
            calendarId = row.calendarId.toULong(),
        )
    }
}
