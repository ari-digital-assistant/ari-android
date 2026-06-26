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
 * NLU. It's also English-only (the one language FunctionGemma was trained
 * for), so non-English installs never use it.
 */
@Singleton
class RouterPolicy @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val downloadManager: RouterDownloadManager,
) {
    suspend fun requiredFromState(): Boolean = required(
        settingsRepository.activeAssistantId.first(),
        settingsRepository.pendingCloudAssistantSetup.first(),
        settingsRepository.activeLocale.first(),
    )

    /**
     * Idempotent — safe to call from every site that can change the
     * assistant or locale (app start, onboarding, settings). When required,
     * enables the router and either loads it or kicks the download. When
     * not, disables it, unloads it from the engine and deletes the 253 MB
     * file to reclaim space.
     */
    suspend fun reconcile(engine: AriEngine, required: Boolean) {
        if (required) {
            if (!settingsRepository.routerEnabled.first()) {
                settingsRepository.setRouterEnabled(true)
            }
            if (downloadManager.isDownloaded()) {
                withContext(Dispatchers.IO) {
                    engine.loadRouterModel(downloadManager.modelFile().absolutePath)
                }
            } else {
                downloadManager.download()
            }
        } else {
            if (settingsRepository.routerEnabled.first()) {
                settingsRepository.setRouterEnabled(false)
            }
            downloadManager.cancel()
            withContext(Dispatchers.IO) {
                engine.unloadRouterModel()
                downloadManager.delete()
            }
        }
    }

    companion object {
        private const val EN = "en"

        /**
         * Pure decision — see [reconcile] for the side effects it drives.
         * Static so it can be unit tested without an Android-backed
         * repository. See `RouterPolicyTest`.
         */
        fun required(activeAssistantId: String?, pendingCloudSetup: Boolean, locale: String): Boolean {
            if (locale != EN) return false
            if (pendingCloudSetup) return false
            return activeAssistantId == null || activeAssistantId == EngineModule.BUILTIN_ASSISTANT_ID
        }
    }
}
