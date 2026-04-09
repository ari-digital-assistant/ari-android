package dev.heyari.ari.skills

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
import uniffi.ari_ffi.FfiRegistryException
import uniffi.ari_ffi.SkillRegistry
import java.util.concurrent.TimeUnit

/**
 * Periodic background worker that hits the registry, diffs the result
 * against what's installed, and posts a notification if there's anything
 * new. Scheduled once from [dev.heyari.ari.AriApplication.onCreate] with
 * `KEEP` policy so reinstalls don't clobber the existing schedule.
 *
 * We deliberately do NOT install updates here. The user opens the app,
 * reviews, and taps install per-skill. Background installs would be a
 * surprise, and a surprise from a voice assistant is a bad thing.
 */
@HiltWorker
class SkillUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val skillRegistry: SkillRegistry,
    private val notifier: SkillUpdateNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Skip the network hop entirely when nothing is installed —
            // check_updates would return empty anyway, but this saves a
            // round trip on fresh installs.
            if (skillRegistry.listInstalled().isEmpty()) {
                Log.i(TAG, "skill update check: no skills installed, skipping")
                notifier.showOrUpdate(0)
                return@withContext Result.success()
            }
            val updates = skillRegistry.checkForUpdates()
            Log.i(TAG, "skill update check: ${updates.size} update(s) available")
            notifier.showOrUpdate(updates.size)
            Result.success()
        } catch (e: FfiRegistryException) {
            // Network blip, bad DNS, registry down — retry next cycle.
            // We don't escalate to the user for transient registry errors.
            Log.w(TAG, "skill update check failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SkillUpdateWorker"
        const val UNIQUE_NAME = "skill-update-check"

        /**
         * Schedule the daily check. Idempotent — `KEEP` policy means
         * subsequent calls are no-ops if the worker is already scheduled.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SkillUpdateWorker>(
                24, TimeUnit.HOURS,
                // Flex window: let WorkManager bundle the run with other
                // scheduled work in the last 6 hours of the 24-hour period.
                6, TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
