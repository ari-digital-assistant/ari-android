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

/**
 * Notices when Ari keeps waking up for nothing, and offers to do something
 * about it.
 *
 * A false wake is the one fault in this app that reports itself. The wake word
 * fires, nobody says anything, the turn times out — and that silence is a
 * confirmed negative with no user judgement in it, which is why nothing here
 * asks the user whether it really was one. Every other wake-word fault is
 * invisible: a phone that has stopped hearing somebody produces no event at
 * all, which is the reason [dev.heyari.ari.data.SettingsRepository.ladderTightenedAt]
 * exists and why this class comes back later to ask.
 *
 * Counting is unconditional and costs nothing but timestamps. Acting on the
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
     * Called from the silence timeout, which is the only place that knows a
     * wake produced nothing. Never throws into that path: a failure to count
     * must not take the voice turn down with it.
     */
    suspend fun onFalseWake(now: Long = System.currentTimeMillis()) {
        try {
            val recent = (prune(read(), now) + now).sorted()
            settingsRepository.setFalseWakeLog(recent.joinToString(","))
            if (recent.size < BURST_THRESHOLD) return
            if (!settingsRepository.tuneDuringNormalUse.first()) {
                // Counted but not acted on. Worth a log line: the rate is the
                // number nobody has ever had, and this is where it shows up.
                Log.i(TAG, "${recent.size} false wakes in ${WINDOW_MS / 3_600_000}h, tuning is off")
                return
            }
            notifyBurst(recent.size)
            // Cleared so the next notification needs a fresh burst rather than
            // arriving again on the very next false wake.
            settingsRepository.setFalseWakeLog("")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to record false wake", t)
        }
    }

    /** False wakes inside the window, for the screen that offers to act on them. */
    suspend fun recentCount(now: Long = System.currentTimeMillis()): Int =
        prune(read(), now).size

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

        /**
         * How long a false wake stays on the books.
         *
         * A day rather than an hour, because the complaint that brings someone
         * to this feature is "it keeps going off", which is a thing people
         * notice across an evening, not inside one.
         */
        const val WINDOW_MS = 24L * 60 * 60 * 1000

        /**
         * False wakes inside [WINDOW_MS] before Ari offers to intervene.
         *
         * PROVISIONAL, and honestly so: nobody has ever measured what a normal
         * false-wake rate looks like on a real phone, so this is the number of
         * times a day that sounded annoying rather than a number anything
         * produced. It is deliberately on the high side — a notification that
         * arrives when nothing is wrong teaches people to dismiss the one that
         * matters. Replace it once `falseWakeLog` has said what real phones do.
         */
        const val BURST_THRESHOLD = 5

        /** Drops anything older than [WINDOW_MS]; also the pruning on every write. */
        fun prune(timestamps: List<Long>, now: Long): List<Long> =
            timestamps.filter { now - it in 0..WINDOW_MS }
    }
}
