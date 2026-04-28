package dev.heyari.ari.models

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
import dev.heyari.ari.data.AutoUpdatePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Periodic background worker that polls each installed on-device model's
 * manifest URL and posts a notification if any version is newer than
 * what's on disk.
 *
 * Mirrors the [dev.heyari.ari.skills.SkillUpdateWorker] shape:
 * - 24h period with a 6h flex window
 * - Scheduled with `KEEP` policy from [dev.heyari.ari.AriApplication.onCreate]
 *   so reinstalls don't reset the cadence
 * - Network-gated; the constraint is reconstructed when the user toggles
 *   "Use mobile data" ([reschedule])
 *
 * Crucially, the worker never installs updates itself. It just notifies.
 * The user opens Settings → Auto-update and decides whether to apply,
 * skip, or postpone. Anything else would be a hostile surprise on
 * multi-GB downloads.
 */
@HiltWorker
class ModelUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checker: ModelUpdateChecker,
    private val notifier: ModelUpdateNotifier,
    private val prefs: AutoUpdatePreferences,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!prefs.enabled.first()) {
            Log.i(TAG, "auto-update disabled by user, skipping")
            notifier.cancel()
            return@withContext Result.success()
        }
        try {
            val updates = checker.checkForUpdates()
            // Only stamp last-checked when we actually got a verdict from
            // the network. A retry-because-offline shouldn't pretend we
            // checked.
            for (category in setOf(
                AutoUpdatePreferences.CATEGORY_ROUTER,
                AutoUpdatePreferences.CATEGORY_LLM,
                AutoUpdatePreferences.CATEGORY_STT,
            )) {
                prefs.setLastChecked(category, Instant.now())
            }
            Log.i(TAG, "model update check: ${updates.size} update(s) available")
            notifier.showOrUpdate(updates)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "model update check failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ModelUpdateWorker"
        const val UNIQUE_NAME = "model-update-check"

        /**
         * Schedule the daily check. [allowMetered] controls whether the
         * worker runs on cellular as well as Wi-Fi; off by default.
         * Idempotent via `KEEP`: subsequent calls are no-ops unless the
         * caller passes `replace = true`, used by the Settings panel
         * when the user flips the metered toggle.
         */
        fun schedule(context: Context, allowMetered: Boolean, replace: Boolean = false) {
            val networkType = if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<ModelUpdateWorker>(
                24, TimeUnit.HOURS,
                6, TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Cancel the scheduled worker. Used when the user disables auto-update. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
