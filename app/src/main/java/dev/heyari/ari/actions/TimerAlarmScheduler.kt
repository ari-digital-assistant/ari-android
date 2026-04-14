package dev.heyari.ari.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.data.timer.Timer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules one-shot `AlarmManager` entries that fire a [TimerExpiryReceiver]
 * broadcast when each timer's `endTsMs` is reached.
 *
 * `WorkManager` can't be used here: its minimum practical latency under Doze
 * is measured in minutes, useless for a 30-second egg timer.
 *
 * Permissions are declared at the manifest level — `USE_EXACT_ALARM` on
 * API 33+ (Play-Store-allowlisted for timer/reminder apps, auto-granted,
 * no prompt) and `SCHEDULE_EXACT_ALARM` on API 31–32 where USE_EXACT_ALARM
 * isn't defined. Either makes `canScheduleExactAlarms()` return true, so
 * `setExactAndAllowWhileIdle` is always the happy path.
 *
 * The `SecurityException` catch handles the rare OEM case (Xiaomi, some
 * Samsung Game Mode paths) where exact-alarm rights get revoked at
 * runtime — falling back to the inexact variant keeps the timer firing
 * rather than silently dropping it.
 *
 * The scheduler is idempotent: calling `schedule(t)` for an already-scheduled
 * timer replaces the previous PendingIntent via `FLAG_UPDATE_CURRENT`.
 */
@Singleton
class TimerAlarmScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val alarms get() = context.getSystemService<AlarmManager>()

    fun schedule(timer: Timer) {
        val am = alarms ?: return
        val pi = pendingFor(timer.id, timer.name, update = true) ?: return
        val triggerAtMs = timer.endTsMs

        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } catch (t: SecurityException) {
            Log.w(TAG, "Exact alarm refused by platform — falling back to inexact", t)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    fun cancel(timerId: String) {
        val am = alarms ?: return
        val pi = pendingFor(timerId, name = null, update = false) ?: return
        am.cancel(pi)
    }

    private fun pendingFor(timerId: String, name: String?, update: Boolean): PendingIntent? {
        val intent = Intent(context, TimerExpiryReceiver::class.java)
            .setAction(TimerExpiryReceiver.ACTION_FIRE)
            .putExtra(TimerExpiryReceiver.EXTRA_TIMER_ID, timerId)
            .putExtra(TimerExpiryReceiver.EXTRA_TIMER_NAME, name)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (update) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, timerId.hashCode(), intent, flags)
    }

    private companion object {
        const val TAG = "TimerAlarm"
    }
}
