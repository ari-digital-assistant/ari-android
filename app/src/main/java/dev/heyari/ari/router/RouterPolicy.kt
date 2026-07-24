package dev.heyari.ari.router

import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.ari_ffi.AriEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether the FunctionGemma skill router should be active and
 * brings its download + engine state in line with that decision.
 *
 * The router is not a user-facing toggle, and it is wanted by everyone: it
 * is the fast tier that answers offline, instantly and for free, and
 * whatever it isn't confident about falls through to the assistant. That
 * holds whether the assistant behind it is the on-device LLM or a cloud
 * one — a cloud assistant is the thing the router saves you a round-trip
 * to, not a reason to go without it.
 *
 * So the only question left is whether a model exists for the language in
 * question — see [shouldHaveModel].
 */
@Singleton
class RouterPolicy @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val downloadManager: RouterDownloadManager,
    private val availability: RouterAvailability,
) {
    /**
     * Serialises reconciles. When reconcile lived inside the engine's
     * one-shot build() it had an implicit mutex; once startup, Settings,
     * Skills and install-completion could all call it, two interleaved
     * reconciles could delete a directory the other had just decided to
     * load. Held across the read-decide-act pair in [reconcileFromState]
     * so the state read and the action stay one unit.
     */
    private val reconcileMutex = Mutex()

    suspend fun requiredFromState(): Boolean =
        shouldHaveModel(settingsRepository.activeLocale.first())

    /**
     * Whether [locale] should have a router model on disk — an install
     * already there, or one [RouterAvailability] says is published.
     *
     * The router is English-only ([routerSupportsLocale]): non-English
     * short-circuits to `false` here without a download or availability
     * check, which is what makes `reconcile` tear down any stale model left
     * from before this became English-only.
     *
     * Takes the locale rather than reading the active one so the onboarding
     * wizard can ask about the language being picked, which isn't active yet.
     *
     * On-disk outranks the probe outright. The probe answers "should I
     * download?", never "should I delete?": the floating release it reads
     * deletes and re-uploads its manifest on every republish, so that URL
     * genuinely 404s for a few seconds every night, forever. A device that
     * probes in that window caches "absent" for a day, and acting on it would
     * cost the user their routing tier plus a 253 MB re-download — on a
     * nightly schedule. Keeping a file that's already there costs nothing and
     * it is still this locale's own model, so no cross-locale rule is in play.
     */
    suspend fun shouldHaveModel(locale: String): Boolean =
        routerSupportsLocale(locale) &&
            (downloadManager.isDownloaded(locale) || availability.isAvailable(locale))

    /**
     * The standard entry point: read required-state and reconcile as one
     * mutex-held unit. Prefer this over calling [requiredFromState] +
     * [reconcile] separately — a decision computed outside the lock can be
     * stale by the time the reconcile acts on it.
     */
    suspend fun reconcileFromState(engine: AriEngine) {
        reconcileMutex.withLock { reconcileLocked(engine, requiredFromState()) }
    }

    /**
     * Idempotent — safe to call from every site that can change the
     * assistant or locale (app start, onboarding, settings). When required,
     * enables the router and either loads the active locale's model or kicks
     * its download. When not, disables it and unloads it from the engine.
     *
     * Either way, every locale directory that isn't the active one is
     * deleted. That's what keeps exactly one 253 MB model on disk across a
     * language switch.
     *
     * [required] is accepted precomputed for the one caller that genuinely
     * knows better than persisted state (the onboarding commit point, where
     * the wizard's choice hasn't been persisted yet).
     */
    suspend fun reconcile(engine: AriEngine, required: Boolean) {
        reconcileMutex.withLock { reconcileLocked(engine, required) }
    }

    private suspend fun reconcileLocked(engine: AriEngine, required: Boolean) {
        val locale = settingsRepository.activeLocale.first()
        if (required) {
            if (!settingsRepository.routerEnabled.first()) {
                settingsRepository.setRouterEnabled(true)
            }
            downloadManager.cancelAndJoinExcept(locale)
            withContext(Dispatchers.IO) { deleteLocalesExcept(locale) }
            if (downloadManager.isDownloaded(locale)) {
                withContext(Dispatchers.IO) {
                    engine.loadRouterWithFloor(downloadManager, locale)
                }
            } else {
                downloadManager.download(locale)
            }
        } else {
            if (settingsRepository.routerEnabled.first()) {
                settingsRepository.setRouterEnabled(false)
            }
            downloadManager.cancelAndJoinExcept(null)
            withContext(Dispatchers.IO) {
                engine.unloadRouterAndFloor()
                deleteLocalesExcept(null)
            }
        }
    }

    private fun deleteLocalesExcept(keep: String?) {
        for (dir in downloadManager.routerRootDir.listFiles().orEmpty()) {
            if (dir.isDirectory && dir.name != keep) dir.deleteRecursively()
        }
    }

    companion object {
        /**
         * Whether the FunctionGemma router covers [locale] at all. It is
         * English-only — at 270M it routes other languages confidently but
         * wrongly — so this is the gate every model decision passes through
         * before any download or availability check. Non-English languages
         * route via the cloud LLM instead (handled in the engine), so they
         * never need a model on disk.
         */
        fun routerSupportsLocale(locale: String): Boolean = locale == "en"
    }
}
