package dev.heyari.ari.listening

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wakes the listening policy at each schedule boundary.
 *
 * A coroutine `delay` would be wrong here for the same reason
 * [dev.heyari.ari.actions.CardAlarmScheduler] gives: Doze suspends the CPU, and
 * a window that opens at 09:00 has to open at 09:00, not whenever the phone next
 * happens to wake up. Only one alarm is ever outstanding — firing it recomputes
 * the state and arms the next boundary, so a hundred schedules still cost one
 * alarm slot.
 */
@Singleton
class ScheduleAlarms @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val alarms get() = context.getSystemService<AlarmManager>()

    private val _boundaries = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits every time a boundary alarm fires. */
    val boundaries: SharedFlow<Unit> = _boundaries.asSharedFlow()

    internal fun onBoundaryReached() {
        _boundaries.tryEmit(Unit)
    }

    /** Replace the outstanding alarm with one at [at], or cancel if null. */
    fun armNext(at: LocalDateTime?) {
        if (at == null) {
            cancel()
            return
        }
        val am = alarms ?: return
        val triggerAtMs = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = boundaryPendingIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } catch (t: SecurityException) {
            Log.w(TAG, "exact alarm refused — falling back to inexact", t)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    fun cancel() {
        val am = alarms ?: return
        val pi = boundaryPendingIntent(PendingIntent.FLAG_NO_CREATE) ?: return
        am.cancel(pi)
    }

    private fun boundaryPendingIntent(extraFlags: Int): PendingIntent? {
        val intent = Intent(context, ListeningBoundaryReceiver::class.java)
            .setAction(ListeningBoundaryReceiver.ACTION_BOUNDARY)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_BOUNDARY,
            intent,
            PendingIntent.FLAG_IMMUTABLE or extraFlags,
        )
    }

    private companion object {
        const val TAG = "ScheduleAlarms"
        const val REQUEST_BOUNDARY = 0
    }
}

/**
 * Receives the boundary alarm and pokes [ScheduleAlarms]. Hilt hands us the same
 * singleton the collecting flow is watching, so there is no other wiring.
 */
@AndroidEntryPoint
class ListeningBoundaryReceiver : BroadcastReceiver() {

    @Inject
    lateinit var scheduleAlarms: ScheduleAlarms

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BOUNDARY) return
        scheduleAlarms.onBoundaryReached()
    }

    companion object {
        const val ACTION_BOUNDARY = "dev.heyari.ari.LISTENING_BOUNDARY"
    }
}
