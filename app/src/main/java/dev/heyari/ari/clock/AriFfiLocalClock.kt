package dev.heyari.ari.clock

import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.FfiLocalClock
import uniffi.ari_ffi.FfiLocalTimeComponents

/**
 * Android implementation of the engine's [FfiLocalClock] callback
 * trait. Reads the device's current locale and timezone via the
 * `java.time` APIs so skills invoking `ari::local_now_components()`
 * or `ari::local_timezone_id()` get real values rather than the
 * UTC fallback.
 *
 * No capability required — every skill can read the wall clock.
 */
@Singleton
class AriFfiLocalClock @Inject constructor() : FfiLocalClock {

    override fun nowComponents(): FfiLocalTimeComponents {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        // Android's DayOfWeek is 1=Monday..7=Sunday; the SDK wants
        // 0=Monday..6=Sunday.
        val weekday = (now.dayOfWeek.value - 1).toUByte()
        return FfiLocalTimeComponents(
            year = now.year,
            month = now.monthValue.toUByte(),
            day = now.dayOfMonth.toUByte(),
            hour = now.hour.toUByte(),
            minute = now.minute.toUByte(),
            second = now.second.toUByte(),
            weekday = weekday,
            tzId = zone.id,
        )
    }

    override fun timezoneId(): String = ZoneId.systemDefault().id
}
