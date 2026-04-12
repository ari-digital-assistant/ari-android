package dev.heyari.ari.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "ari_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val activeSttModelId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_STT_MODEL]
    }

    suspend fun setActiveSttModelId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_STT_MODEL)
            else prefs[KEY_ACTIVE_STT_MODEL] = id
        }
    }

    val activeWakeWordId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_WAKE_WORD]
    }

    suspend fun setActiveWakeWordId(id: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_WAKE_WORD] = id
        }
    }

    val wakeWordSensitivity: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_WAKE_WORD_SENSITIVITY]
    }

    suspend fun setWakeWordSensitivity(name: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WAKE_WORD_SENSITIVITY] = name
        }
    }

    /** The selected LLM tier id, or "none" / null if disabled. */
    val activeLlmModelId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_LLM_MODEL]
    }

    suspend fun setActiveLlmModelId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_LLM_MODEL)
            else prefs[KEY_ACTIVE_LLM_MODEL] = id
        }
    }

    /** The active assistant skill ID, or null if none. */
    val activeAssistantId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_ASSISTANT]
    }

    suspend fun setActiveAssistantId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_ASSISTANT)
            else prefs[KEY_ACTIVE_ASSISTANT] = id
        }
    }

    /**
     * Whether to start the wake word service on device boot. Default off —
     * auto-starting a microphone FGS is a privacy-visible behaviour we only
     * want happening when the user has explicitly said yes.
     */
    val startOnBoot: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_START_ON_BOOT] ?: false
    }

    suspend fun setStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_START_ON_BOOT] = enabled
        }
    }

    /**
     * Read/write per-assistant config values. Scoped by skill ID + key.
     * Used for non-secret config (model name, endpoint URL, etc.).
     */
    fun assistantConfigValue(skillId: String, key: String): Flow<String?> =
        context.dataStore.data.map { prefs ->
            prefs[stringPreferencesKey("assistant_config_${skillId}_${key}")]
        }

    suspend fun setAssistantConfigValue(skillId: String, key: String, value: String?) {
        val prefKey = stringPreferencesKey("assistant_config_${skillId}_${key}")
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(prefKey)
            else prefs[prefKey] = value
        }
    }

    companion object {
        private val KEY_ACTIVE_STT_MODEL = stringPreferencesKey("active_stt_model")
        private val KEY_ACTIVE_WAKE_WORD = stringPreferencesKey("active_wake_word")
        private val KEY_WAKE_WORD_SENSITIVITY = stringPreferencesKey("wake_word_sensitivity")
        private val KEY_ACTIVE_LLM_MODEL = stringPreferencesKey("active_llm_model")
        private val KEY_ACTIVE_ASSISTANT = stringPreferencesKey("active_assistant")
        private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
    }
}
