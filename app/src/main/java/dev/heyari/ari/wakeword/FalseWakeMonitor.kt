package dev.heyari.ari.wakeword

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.MainActivity
import dev.heyari.ari.R
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.falseWakeBurstDue
import uniffi.ari_ffi.falseWakePrune
import uniffi.ari_ffi.falseWakeWindowHours

/**
 * Notices when Ari keeps waking up for nothing, and offers to do something
 * about it.
 *
 * A false wake is the one fault in this app that reports itself. The wake word
 * fires and nobody called Ari: the turn times out in silence, the transcript
 * holds no speech, or the speech holds no "Ari". Each is a negative with no
 * user judgement in it, which is why nothing here asks the user whether it
 * really was one. Every other wake-word fault is
 * invisible: a phone that has stopped hearing somebody produces no event at
 * all, which is the reason [dev.heyari.ari.data.SettingsRepository.ladderTightenedAt]
 * exists and why this class comes back later to ask.
 *
 * The voice session (ari-voice) decides what a false wake is, and the window
 * and burst size are the engine's (`false_wake`); this keeps the timestamps
 * and raises the notification. Counting is unconditional and costs nothing
 * but timestamps. Acting on the
 * count requires `tuneDuringNormalUse`, because changing how the assistant
 * behaves on the strength of something the user never reported is a decision
 * they have to have opted into.
 *
 * No audio passes through here. The event is enough to count, and keeping the
 * clip would put microphone recordings behind a switch whose description
 * promises the opposite.
 */
@Singleton
class FalseWakeMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    /**
     * Record a false wake and, if they are coming thick and fast, say so.
     *
     * Called by the voice session for every false wake the user saw. Never
     * throws into that path: a failure to count must not take the voice turn
     * down with it.
     */
    suspend fun onFalseWake(now: Long = System.currentTimeMillis()) {
        try {
            val recent = (falseWakePrune(read(), now) + now).sorted()
            settingsRepository.setFalseWakeLog(recent.joinToString(","))
            val notifiedAt = settingsRepository.falseWakeNotifiedAt.first()
            if (!falseWakeBurstDue(recent, notifiedAt)) return
            if (!settingsRepository.tuneDuringNormalUse.first()) {
                // Counted but not acted on. Worth a log line: the rate is the
                // number nobody has ever had, and this is where it shows up.
                Log.i(TAG, "${recent.size} false wakes in ${falseWakeWindowHours()}h, tuning is off")
                return
            }
            Log.i(TAG, "${recent.size} false wakes in ${falseWakeWindowHours()}h, offering to help")
            notifyBurst(recent.size)
            // The log itself is kept: the screen this notification opens
            // reads its count from it, and clearing it here made that screen
            // say Ari had woken up zero times.
            settingsRepository.setFalseWakeNotifiedAt(now)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to record false wake", t)
        }
    }

    /** False wakes inside the window, for the screen that offers to act on them. */
    suspend fun recentCount(now: Long = System.currentTimeMillis()): Int =
        falseWakePrune(read(), now).size

    private suspend fun read(): List<Long> =
        settingsRepository.falseWakeLog.first()
            ?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?: emptyList()

    private fun notifyBurst(count: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_REVIEW_FALSE_WAKES
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context, REQUEST_REVIEW, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, WakeWordService.CHANNEL_TUNING)
            .setContentTitle(context.getString(R.string.notif_false_wake_burst_title))
            .setContentText(context.resources.getQuantityString(
                R.plurals.notif_false_wake_burst_text, count, count))
            .setSmallIcon(R.drawable.ic_ari_symbolic)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "FalseWakeMonitor"

        const val ACTION_REVIEW_FALSE_WAKES = "dev.heyari.ari.REVIEW_FALSE_WAKES"
        private const val REQUEST_REVIEW = 5101
        private const val NOTIFICATION_ID = 5102
    }
}
