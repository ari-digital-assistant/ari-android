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
 * NLU. It also needs a model published for the active locale, which
 * [RouterAvailability] answers over the network.
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
        return availability.isAvailable(settingsRepository.activeLocale.first())
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
                    engine.loadRouterModel(downloadManager.modelFile(locale).absolutePath)
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
                engine.unloadRouterModel()
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
