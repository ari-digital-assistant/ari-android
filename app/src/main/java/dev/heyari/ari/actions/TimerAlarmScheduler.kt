package dev.heyari.ari.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
 * is measured in minutes, which makes it useless for a 30-second egg timer.
 * `AlarmManager` with `setExactAndAllowWhileIdle` is the right primitive —
 * the permission story is:
 *
 * - API 29–30: normal permission, granted at install.
 * - API 31–32: normal permission, still granted at install.
 * - API 33+: requires `SCHEDULE_EXACT_ALARM` (user-grantable). When the
 *   permission is not granted we fall back to `setAndAllowWhileIdle`, which
 *   is inexact but still fires. Documented in the plan under "acceptable UX
 *   tradeoff" — the completion sound can be off by up to a Doze window for
 *   the long-tail case where the app has been backgrounded past the user
 *   refusing the permission. We surface this in the settings page (future
 *   work) rather than prompting the user mid-timer.
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

        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            am.canScheduleExactAlarms()

        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                Log.i(
                    TAG,
                    "SCHEDULE_EXACT_ALARM not granted — timer ${timer.id} will fire " +
                        "inexactly (Doze window may delay completion alert)",
                )
            }
        } catch (t: SecurityException) {
            // Rare: some OEMs revoke mid-flight. Fall back to inexact so we
            // still do something rather than silently dropping the timer.
            Log.w(TAG, "Exact alarm scheduling refused, falling back to inexact", t)
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
