package dev.heyari.ari.actions

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Honours the engine's `alarm` action by handing off to the platform Clock app
 * via the public `AlarmClock` intent family. `op:"set"` creates an alarm
 * silently (EXTRA_SKIP_UI); `op:"show"` opens the alarm list.
 *
 * The API is write-and-show only — it cannot enumerate or delete alarms.
 */
@Singleton
class AlarmLauncher @Inject constructor(
    private val context: Context,
) {
    sealed interface LaunchResult {
        object Launched : LaunchResult
        object NoClockApp : LaunchResult
    }

    fun launch(action: AlarmAction): LaunchResult {
        val intent = when (action.op) {
            "show" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            else -> buildSetIntent(action)
        }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        return try {
            context.startActivity(intent)
            LaunchResult.Launched
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "no clock app to handle ${action.op}", e)
            LaunchResult.NoClockApp
        }
    }

    private fun buildSetIntent(action: AlarmAction): Intent =
        Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            action.hour?.let { putExtra(AlarmClock.EXTRA_HOUR, it) }
            action.minute?.let { putExtra(AlarmClock.EXTRA_MINUTES, it) }
            action.message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            val calDays = action.days.mapNotNull { dayCodeToCalendar(it) }
            if (calDays.isNotEmpty()) {
                putExtra(AlarmClock.EXTRA_DAYS, ArrayList(calDays))
            }
        }

    companion object {
        private const val TAG = "AlarmLauncher"

        /** Map a lowercase 3-letter day code to a java.util.Calendar constant. */
        fun dayCodeToCalendar(code: String): Int? = when (code) {
            "sun" -> Calendar.SUNDAY
            "mon" -> Calendar.MONDAY
            "tue" -> Calendar.TUESDAY
            "wed" -> Calendar.WEDNESDAY
            "thu" -> Calendar.THURSDAY
            "fri" -> Calendar.FRIDAY
            "sat" -> Calendar.SATURDAY
            else -> null
        }
    }
}
