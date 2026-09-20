package dev.heyari.ari.contrib

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodic background worker that sends whatever new clips the user has agreed
 * to contribute.
 *
 * Scheduled only while something is actually shared — see
 * [ContributionUploader.syncSchedule] — so an install that never opted in has
 * no work registered at all.
 *
 * Unmetered network rather than a Wi-Fi check: it is the same thing in
 * practice, and it additionally respects a Wi-Fi network the user has marked
 * as metered, which a transport check would trample straight over.
 */
@HiltWorker
class ContributionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val uploader: ContributionUploader,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        when (val outcome = uploader.sweep()) {
            is SweepOutcome.Idle -> Result.success()
            is SweepOutcome.Sent -> {
                Log.i(TAG, "contributed ${outcome.clips} clip(s)")
                Result.success()
            }
            is SweepOutcome.Retry -> Result.retry()
        }
    }

    companion object {
        private const val TAG = "ContributionWorker"
        const val UNIQUE_NAME = "recording-contribution"

        /**
         * Schedule the sweep. Idempotent — `KEEP` means a second call leaves
         * the existing schedule alone rather than restarting its period, so
         * toggling a switch twice does not postpone the next run.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<ContributionWorker>(
                6, TimeUnit.HOURS,
                // Flex window: WorkManager can bundle the run with other work
                // in the last two hours of each period.
                2, TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
