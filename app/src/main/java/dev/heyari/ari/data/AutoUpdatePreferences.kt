package dev.heyari.ari.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private val Context.autoUpdateDataStore by preferencesDataStore(name = "ari_auto_update_prefs")

/**
 * Persists the auto-update toggle, network policy, last-check timestamps,
 * and per-model "skip this version" choices for on-device models
 * (FunctionGemma router, on-device LLM tiers, STT bundle).
 *
 * Master toggle defaults to ON: opt-out, not opt-in. Metered toggle
 * defaults to OFF — Wi-Fi only — because GGUFs run from 250 MB to 5 GB
 * and silently chewing through a data plan would be a hostile default.
 *
 * Skipped versions are keyed by an opaque model identifier (e.g.
 * `router`, `gemma3-1b-q4`, `sherpa-onnx-streaming-zipformer-en-2023-06-26`).
 * The auto-update checker compares the available manifest version against
 * the stored skip; only an exact match is suppressed, so a newer release
 * automatically un-skips itself.
 */
@Singleton
class AutoUpdatePreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val enabled: Flow<Boolean> = context.autoUpdateDataStore.data.map { prefs ->
        prefs[KEY_ENABLED] ?: true
    }

    val allowMetered: Flow<Boolean> = context.autoUpdateDataStore.data.map { prefs ->
        prefs[KEY_ALLOW_METERED] ?: false
    }

    /**
     * Set when a pre-per-locale router install was adopted in place. Drives
     * exactly one forced background upgrade onto the real `-en-` model —
     * the adopted artifact is frozen and was never evaluated at the current
     * confidence threshold, so leaving users on it indefinitely isn't a
     * neutral default.
     */
    val legacyRouterAdopted: Flow<Boolean> = context.autoUpdateDataStore.data.map { prefs ->
        prefs[KEY_LEGACY_ROUTER_ADOPTED] ?: false
    }

    fun lastChecked(category: String): Flow<Instant?> = context.autoUpdateDataStore.data.map { prefs ->
        prefs[lastCheckedKey(category)]?.let(Instant::ofEpochMilli)
    }

    fun skippedVersion(modelKey: String): Flow<String?> = context.autoUpdateDataStore.data.map { prefs ->
        prefs[skippedKey(modelKey)]
    }

    suspend fun setEnabled(value: Boolean) {
        context.autoUpdateDataStore.edit { it[KEY_ENABLED] = value }
    }

    suspend fun setAllowMetered(value: Boolean) {
        context.autoUpdateDataStore.edit { it[KEY_ALLOW_METERED] = value }
    }

    suspend fun setLegacyRouterAdopted(value: Boolean) {
        context.autoUpdateDataStore.edit { it[KEY_LEGACY_ROUTER_ADOPTED] = value }
    }

    suspend fun setLastChecked(category: String, instant: Instant) {
        context.autoUpdateDataStore.edit { it[lastCheckedKey(category)] = instant.toEpochMilli() }
    }

    suspend fun setSkippedVersion(modelKey: String, version: String?) {
        context.autoUpdateDataStore.edit { prefs ->
            val key = skippedKey(modelKey)
            if (version == null) prefs.remove(key) else prefs[key] = version
        }
    }

    /** Drop every skip entry. Surfaced via "Reset skipped versions" in the UI. */
    suspend fun clearAllSkippedVersions() {
        context.autoUpdateDataStore.edit { prefs ->
            val toRemove = prefs.asMap().keys.filter { it.name.startsWith(SKIPPED_PREFIX) }
            for (key in toRemove) prefs.remove(key)
        }
    }

    companion object {
        const val CATEGORY_ROUTER = "router"
        const val CATEGORY_LLM = "llm"
        const val CATEGORY_STT = "stt"

        private val KEY_ENABLED = booleanPreferencesKey("auto_update_enabled")
        private val KEY_ALLOW_METERED = booleanPreferencesKey("auto_update_metered")
        private val KEY_LEGACY_ROUTER_ADOPTED = booleanPreferencesKey("legacy_router_adopted")
        private const val SKIPPED_PREFIX = "skipped_version_"

        private fun lastCheckedKey(category: String) = longPreferencesKey("last_checked_$category")
        private fun skippedKey(modelKey: String) = stringPreferencesKey("$SKIPPED_PREFIX$modelKey")
    }
}
