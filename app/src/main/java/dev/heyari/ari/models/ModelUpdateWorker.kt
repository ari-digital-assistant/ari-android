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
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.router.RouterModel
import dev.heyari.ari.updates.UpdatesPreferences
import dev.heyari.ari.updates.UpdatesRepository
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
 * The worker doesn't install updates — it notifies, and the user decides in
 * Settings → Auto-update whether to apply, skip, or postpone. Anything else
 * would be a hostile surprise on multi-GB downloads. The single exception is
 * [forceLegacyRouterUpgrade], which takes pre-per-locale installs off the
 * frozen router artifact once; see that function for why it earns the
 * exception.
 */
@HiltWorker
class ModelUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checker: ModelUpdateChecker,
    private val applier: ModelUpdateApplier,
    private val prefs: AutoUpdatePreferences,
    private val updatesRepository: UpdatesRepository,
    private val routerDownloadManager: dev.heyari.ari.router.RouterDownloadManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!prefs.enabled.first()) {
            Log.i(TAG, "auto-update disabled by user, skipping")
            updatesRepository.recordCheck(UpdatesPreferences.Category.MODEL, emptyList())
            return@withContext Result.success()
        }
        try {
            val updates = checker.checkForUpdates()
            // Ring-fenced from the routine check on purpose. The applier goes
            // through EngineHolder.engine(), and a permanently poisoned engine
            // build makes every call to it throw — which, uncaught here, would
            // cost this user *all* model update notifications rather than just
            // the one silent upgrade.
            val applied = try {
                forceLegacyRouterUpgrade(updates)
            } catch (e: Exception) {
                Log.w(TAG, "forced router upgrade threw; routine check continues", e)
                null
            }
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
            // An update we just installed is not pending. Recording it anyway
            // would leave the banner (and, past the inactivity threshold, a
            // notification) offering a version already on disk for the ~24h
            // until the next run, and tapping it would re-download 253 MB.
            val pending = if (applied == null) updates else updates.filterNot { it === applied }
            Log.i(TAG, "model update check: ${pending.size} update(s) available")
            updatesRepository.recordCheck(
                UpdatesPreferences.Category.MODEL,
                UpdatesRepository.summariesFromModelUpdates(pending, applicationContext::getString),
            )
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "model update check failed: ${e.message}")
            Result.retry()
        }
    }

    /**
     * The one exception to "this worker never installs anything". A user
     * whose legacy router was adopted in place is running a frozen artifact
     * under a confidence threshold calibrated for a different model, so we
     * take them off it once without waiting for a tap. It rides the same
     * network constraint as the check itself, so a user who hasn't opted
     * into metered downloads still won't get one.
     *
     * Returns the update it installed, so the caller can keep an
     * already-applied entry out of the pending list; null if it did nothing.
     */
    private suspend fun forceLegacyRouterUpgrade(updates: List<ModelUpdate>): ModelUpdate? {
        val adoptedVersion = prefs.adoptedRouterVersion.first() ?: return null

        // The marker names the exact artifact it may replace. Anything else
        // on disk — the user applied an update from Settings, a
        // settings-path download replaced it, the model was deleted — means
        // the marker has outlived its subject: disarm rather than silently
        // force-installing over a model the migration never touched. This
        // check is also what lets the marker stay safely armed across
        // manifest-fetch failures and locale switches: it can never fire
        // against anything but the frozen adopted artifact.
        if (routerDownloadManager.installedVersion(RouterModel.LEGACY_LOCALE) != adoptedVersion) {
            prefs.setAdoptedRouterVersion(null)
            Log.i(TAG, "legacy router marker disarmed: installed en version no longer $adoptedVersion")
            return null
        }

        // Never anything but the locale the legacy artifact was trained for.
        // The checker builds its Router target from the *active* locale, so an
        // adopted user who has since switched to Italian would otherwise have
        // their `it` router silently force-installed.
        // No update pending for `en` this run (other locale active, fetch
        // failure, or user explicitly skipped the version): stay armed and
        // return — the version pin above makes a stale marker harmless.
        val update = updates.firstOrNull {
            (it.target as? ModelTarget.Router)?.locale == RouterModel.LEGACY_LOCALE
        } ?: return null

        var failure: String? = null
        applier.apply(update).collect { event ->
            if (event is ApplyEvent.Failed) failure = event.reason
        }
        if (failure != null) {
            Log.w(TAG, "forced router upgrade failed, will retry: $failure")
            return null
        }
        prefs.setAdoptedRouterVersion(null)
        Log.i(TAG, "forced router upgrade complete: ${update.availableVersion}")
        return update
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
