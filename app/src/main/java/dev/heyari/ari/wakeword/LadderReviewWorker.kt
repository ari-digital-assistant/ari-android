package dev.heyari.ari.wakeword

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.MainActivity
import dev.heyari.ari.R
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What the review asks about, and how long it waits before asking. */
internal object LadderReview {
    const val WORK_NAME = "wake-ladder-review"

    /**
     * How long to live with a tightening before asking about it.
     *
     * Long enough for everybody in a household to have tried to use the thing
     * at least once — a person who only talks to Ari at breakfast needs two
     * mornings to notice — and short enough that they have not yet given up and
     * stopped trying, which is the failure this question is racing.
     */
    const val DELAY_HOURS = 48L

    const val ACTION_REVIEW_TIGHTENING = "dev.heyari.ari.REVIEW_TIGHTENING"
}

/**
 * Comes back after Ari has tightened its own sensitivity and asks whether that
 * was a mistake.
 *
 * This exists because the two faults are not symmetrical. A wake word that
 * fires too easily announces itself every time; a wake word that has stopped
 * hearing somebody produces nothing at all — no event, no log line, and no
 * complaint from the person who has quietly given up on it. The app shipped for
 * five months in exactly that state. Every automatic tightening therefore comes
 * with a scheduled question, and the question is the only feedback path the
 * quiet direction has.
 *
 * Deliberately driven by the clock and not by wake activity. Counting successful
 * wakes as the signal to ask would be measuring the people the tightening did
 * not affect: if it went deaf to one member of the household, the wakes still
 * arriving belong to everybody else and the counter fills up looking healthy.
 */
@HiltWorker
class LadderReviewWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Zero means the question has already been answered, or the tightening
        // was undone by hand before this ran. Either way there is nothing left
        // to ask, and asking anyway would be about a ladder nobody is running.
        if (settingsRepository.ladderTightenedAt.first() == 0L) return Result.success()

        Log.i(TAG, "Asking whether the last self-tightening is still working")
        val intent = Intent(context, MainActivity::class.java).apply {
            action = LadderReview.ACTION_REVIEW_TIGHTENING
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context, REQUEST_REVIEW, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, WakeWordService.CHANNEL_TUNING)
            .setContentTitle(context.getString(R.string.notif_ladder_review_title))
            .setContentText(context.getString(R.string.notif_ladder_review_text))
            .setSmallIcon(R.drawable.ic_ari_symbolic)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
        return Result.success()
    }

    private companion object {
        const val TAG = "LadderReviewWorker"
        const val REQUEST_REVIEW = 5103
        const val NOTIFICATION_ID = 5104
    }
}

/**
 * Schedules [LadderReviewWorker], so callers do not have to know about
 * WorkManager and the schedule is set and cancelled in one place.
 */
@Singleton
class LadderReviewScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun schedule() {
        val request = OneTimeWorkRequestBuilder<LadderReviewWorker>()
            .setInitialDelay(LadderReview.DELAY_HOURS, TimeUnit.HOURS)
            .build()
        // REPLACE: a second tightening restarts the clock, because the question
        // that matters is about the ladder in force now, not the one before it.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(LadderReview.WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(LadderReview.WORK_NAME)
    }
}
