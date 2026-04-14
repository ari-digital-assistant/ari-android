package dev.heyari.ari.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.heyari.ari.data.timer.TimerStateRepository
import dev.heyari.ari.notifications.TimerNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uniffi.ari_ffi.AriEngine
import javax.inject.Inject

/**
 * Receiver for the three timer-lifecycle broadcasts:
 *
 * 1. [ACTION_FIRE] — the `AlarmManager` entry set by [TimerAlarmScheduler]
 *    reached its trigger time. Post the completion notification, clear the
 *    ongoing one, and tell the skill so its `storage_kv` stays authoritative
 *    (the skill will prune the expired entry on its next call anyway, but
 *    nudging it keeps state aligned between utterances).
 * 2. [ACTION_CANCEL_FROM_NOTIFICATION] — user tapped "Cancel" on the
 *    ongoing notification. Same skill roundtrip as a UI cancel.
 * 3. [ACTION_DISMISS_ALERT] — user tapped "Stop" on the completion
 *    notification. Just clears the alert; the timer is already gone.
 */
@AndroidEntryPoint
class TimerExpiryReceiver : BroadcastReceiver() {

    @Inject lateinit var notifier: TimerNotifier
    @Inject lateinit var repository: TimerStateRepository
    @Inject lateinit var scheduler: TimerAlarmScheduler
    @Inject lateinit var engine: AriEngine

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_TIMER_ID) ?: return
        val name = intent.getStringExtra(EXTRA_TIMER_NAME)

        when (intent.action) {
            ACTION_FIRE -> onFire(id, name)
            ACTION_CANCEL_FROM_NOTIFICATION -> onCancelFromNotification(id, name)
            ACTION_DISMISS_ALERT -> notifier.dismissCompletion(id)
            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
    }

    private fun onFire(id: String, name: String?) {
        notifier.dismissOngoing(id)
        notifier.showCompletion(id, name)
        repository.removeById(id)
        // Keep the skill aligned. Fire-and-forget — the response will come
        // back as a normal envelope and reconcile via TimerCoordinator.
        scope.launch {
            runCatching {
                val phrase = name?.let { "cancel my $it timer" } ?: "cancel my timer"
                engine.processInput(phrase)
            }.onFailure { Log.w(TAG, "skill nudge on expiry failed", it) }
        }
    }

    private fun onCancelFromNotification(id: String, name: String?) {
        notifier.dismissOngoing(id)
        scheduler.cancel(id)
        scope.launch {
            runCatching {
                val phrase = name?.let { "cancel my $it timer" } ?: "cancel my timer"
                engine.processInput(phrase)
            }.onFailure { Log.w(TAG, "skill cancel via notification failed", it) }
        }
    }

    companion object {
        const val ACTION_FIRE = "dev.heyari.ari.TIMER_FIRE"
        const val ACTION_CANCEL_FROM_NOTIFICATION = "dev.heyari.ari.TIMER_CANCEL_NOTIF"
        const val ACTION_DISMISS_ALERT = "dev.heyari.ari.TIMER_DISMISS_ALERT"
        const val EXTRA_TIMER_ID = "timer_id"
        const val EXTRA_TIMER_NAME = "timer_name"

        private const val TAG = "TimerExpiryReceiver"

        // Receivers don't have a ViewModelScope; we own a SupervisorJob at
        // the class-loader scope so engine.processInput coroutines can
        // outlive `onReceive`'s 10-second budget. The IO dispatcher is fine
        // because processInput itself blocks briefly before returning.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
