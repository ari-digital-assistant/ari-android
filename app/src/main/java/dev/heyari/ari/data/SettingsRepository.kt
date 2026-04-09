package dev.heyari.ari.data

import android.content.Context
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

    companion object {
        private val KEY_ACTIVE_STT_MODEL = stringPreferencesKey("active_stt_model")
        private val KEY_ACTIVE_WAKE_WORD = stringPreferencesKey("active_wake_word")
        private val KEY_WAKE_WORD_SENSITIVITY = stringPreferencesKey("wake_word_sensitivity")
    }
}
