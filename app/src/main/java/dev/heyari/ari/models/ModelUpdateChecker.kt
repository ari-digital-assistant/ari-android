package dev.heyari.ari.models

import androidx.annotation.StringRes
import android.util.Log
import dev.heyari.ari.data.AutoUpdatePreferences
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.llm.LlmDownloadManager
import dev.heyari.ari.llm.LlmModel
import dev.heyari.ari.llm.LlmModelRegistry
import dev.heyari.ari.stt.ModelDownloadManager
import dev.heyari.ari.stt.SttModel
import dev.heyari.ari.stt.SttModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identifies one updateable on-device model. Carries the durable key used
 * for sidecar lookups + DataStore "skipped version" entries, plus a
 * human-readable display name surfaced in notifications and the
 * Settings panel.
 */
sealed interface ModelTarget {
    val key: String
    val category: String
    @get:StringRes val displayNameRes: Int

    data class Llm(val model: LlmModel) : ModelTarget {
        override val key: String get() = model.id
        override val category: String get() = AutoUpdatePreferences.CATEGORY_LLM
        override val displayNameRes: Int get() = model.displayNameRes
    }

    data class Stt(val model: SttModel) : ModelTarget {
        override val key: String get() = model.id
        override val category: String get() = AutoUpdatePreferences.CATEGORY_STT
        override val displayNameRes: Int get() = model.displayNameRes
    }

    companion object {
        /**
         * Display-name resource for a stable [key], or null when no shipped
         * model owns it.
         *
         * Exists so persisted state can hold the KEY and resolve the name on
         * read. An R id must never be persisted: resource ids are assigned at
         * build time and move between builds, so a stored one would silently
         * come back pointing at a different string after an app update. Keys
         * are ours and stable.
         *
         * Null is a real answer, not an error — a summary written before a
         * model was retired (Nemotron) names something this build cannot
         * resolve, and the caller should fall back to whatever text it stored
         * at the time.
         */
        @StringRes
        fun displayNameResFor(key: String): Int? =
            LlmModelRegistry.byId(key)?.displayNameRes
                ?: SttModelRegistry.byId(key)?.displayNameRes
    }
}

/**
 * One pending update — produced by [ModelUpdateChecker.checkForUpdates],
 * consumed by [ModelUpdateNotifier] (to decide whether to fire) and
 * [dev.heyari.ari.ui.settings.SettingsViewModel] (to drive the
 * download → verify → swap → reload sequence).
 */
data class ModelUpdate(
    val target: ModelTarget,
    val installedVersion: String,
    val manifest: ModelManifest,
) {
    val availableVersion: String get() = manifest.version
    val sizeBytes: Long get() = manifest.totalSizeBytes
}

/**
 * Polls the manifest endpoint for each installed on-device model and
 * returns the set of pending updates.
 *
 * "Installed" is the gate: we don't probe for tier B updates if the user
 * picked tier A — the network round-trip is wasted, and we'd surface
 * notifications for things they don't have.
 *
 * Covers the on-device LLM and STT models.
 */
@Singleton
class ModelUpdateChecker @Inject constructor(
    private val llmDownloadManager: LlmDownloadManager,
    private val sttDownloadManager: ModelDownloadManager,
    private val settingsRepository: SettingsRepository,
    private val autoUpdatePreferences: AutoUpdatePreferences,
) {
    suspend fun checkForUpdates(): List<ModelUpdate> = withContext(Dispatchers.IO) {
        val updates = mutableListOf<ModelUpdate>()

        // Only check the *active* LLM tier — probing for updates on tiers
        // the user didn't pick wastes a network round-trip and would
        // surface notifications for things they don't have.
        val activeLlmId = settingsRepository.activeLlmModelId.first()
        val activeLlm = LlmModelRegistry.byId(activeLlmId)
        if (activeLlm != null
            && llmDownloadManager.isDownloaded(activeLlm)
            && activeLlm.manifestUrl.isNotBlank()
        ) {
            checkOne(
                target = ModelTarget.Llm(activeLlm),
                manifestUrl = activeLlm.manifestUrl,
                installedVersion = llmDownloadManager.installedVersion(activeLlm),
            )?.let(updates::add)
        }

        // Active STT model only.
        val activeSttId = settingsRepository.activeSttModelId.first()
        val activeStt = SttModelRegistry.byId(activeSttId)
        if (activeStt != null
            && sttDownloadManager.isDownloaded(activeStt)
            && activeStt.manifestUrl.isNotBlank()
        ) {
            checkOne(
                target = ModelTarget.Stt(activeStt),
                manifestUrl = activeStt.manifestUrl,
                installedVersion = sttDownloadManager.installedVersion(activeStt),
            )?.let(updates::add)
        }

        updates
    }

    private suspend fun checkOne(
        target: ModelTarget,
        manifestUrl: String,
        installedVersion: String,
    ): ModelUpdate? {
        val manifest = fetchManifest(manifestUrl) ?: return null
        if (manifest.version == installedVersion) return null

        val skipped = autoUpdatePreferences.skippedVersion(target.key).first()
        if (skipped == manifest.version) {
            Log.i(TAG, "${target.key}: ${manifest.version} matches user-skipped version, suppressing")
            return null
        }

        return ModelUpdate(target = target, installedVersion = installedVersion, manifest = manifest)
    }

    private fun fetchManifest(manifestUrl: String): ModelManifest? = try {
        val conn = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            connect()
        }
        if (conn.responseCode != 200) {
            Log.w(TAG, "manifest fetch returned HTTP ${conn.responseCode}: $manifestUrl")
            null
        } else {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            ModelManifest.parse(text)
        }
    } catch (e: Exception) {
        Log.w(TAG, "manifest fetch failed for $manifestUrl: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "ModelUpdateChecker"
    }
}
