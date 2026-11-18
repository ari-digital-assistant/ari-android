package dev.heyari.ari.bugreport

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Tells a tester when someone replies to a bug report they filed. GitHub only
 * tells them if they go and look, and most never will.
 *
 * Asks heyari.dev rather than GitHub: the phone already talks to it to file
 * reports, so nobody new learns the tester's address, and the server leaves
 * bots' comments out of the count. A report the server no longer knows,
 * withdrawn or expired, is skipped.
 */
@HiltWorker
class ReportReplyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val client: BugReportClient,
    private val notifier: ReportReplyNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val reports = settings.filedReports.first().filterNot { it.isExpired(now) }
        for (report in reports) {
            val replies = try {
                client.replies(report.reportId, report.deleteToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "reply check failed, trying again later", e)
                return Result.retry()
            } ?: continue
            if (replies > report.seenReplies) notifier.notify(report)
            // Lower too, when a reply was deleted, so the next one still counts as new.
            if (replies != report.seenReplies) settings.setSeenReplies(report.reportId, replies)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "ReportReplyWorker"
        private const val UNIQUE_NAME = "report-reply-check"

        /** Twice a day. `KEEP`, so an app update doesn't restart the clock. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReportReplyWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
