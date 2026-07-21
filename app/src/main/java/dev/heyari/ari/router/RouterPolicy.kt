package dev.heyari.ari.router

import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.di.EngineModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import uniffi.ari_ffi.AriEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether the FunctionGemma skill router should be active and
 * brings its download + engine state in line with that decision.
 *
 * The router is no longer a user-facing toggle. It's essential when Ari
 * has to understand commands on its own — the built-in on-device assistant
 * or no assistant at all — but redundant when a cloud assistant does the
 * NLU. It also needs a model for the locale in question — see
 * [shouldHaveModel].
 */
@Singleton
class RouterPolicy @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val downloadManager: RouterDownloadManager,
    private val availability: RouterAvailability,
) {
    suspend fun requiredFromState(): Boolean {
        // Cheap local decision first — no point spending a network probe to
        // discover a model we wouldn't use anyway.
        val wanted = required(
            settingsRepository.activeAssistantId.first(),
            settingsRepository.pendingCloudAssistantSetup.first(),
        )
        if (!wanted) return false
        return shouldHaveModel(settingsRepository.activeLocale.first())
    }

    /**
     * Whether [locale] should have a router model on disk — an install
     * already there, or one [RouterAvailability] says is published.
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
        downloadManager.isDownloaded(locale) || availability.isAvailable(locale)

    /**
     * Idempotent — safe to call from every site that can change the
     * assistant or locale (app start, onboarding, settings). When required,
     * enables the router and either loads the active locale's model or kicks
     * its download. When not, disables it and unloads it from the engine.
     *
     * Either way, every locale directory that isn't the active one is
     * deleted. That's what keeps exactly one 253 MB model on disk across a
     * language switch.
     */
    suspend fun reconcile(engine: AriEngine, required: Boolean) {
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
         * Pure decision — see [reconcile] for the side effects it drives.
         * Static so it can be unit tested without an Android-backed
         * repository. See `RouterPolicyTest`.
         */
        fun required(activeAssistantId: String?, pendingCloudSetup: Boolean): Boolean {
            if (pendingCloudSetup) return false
            return activeAssistantId == null || activeAssistantId == EngineModule.BUILTIN_ASSISTANT_ID
        }
    }
}
