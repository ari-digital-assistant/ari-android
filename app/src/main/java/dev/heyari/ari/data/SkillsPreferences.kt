package dev.heyari.ari.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private val Context.skillsDataStore by preferencesDataStore(name = "ari_skills_prefs")

/**
 * Persists the Skills screen's "last checked" timestamps, so they survive app
 * restarts rather than resetting every launch, and its "Hide installed" choice.
 *
 * Stored as epoch milliseconds (Long) because [Instant] isn't a
 * first-class DataStore type. Zero / absent means "never checked".
 */
@Singleton
class SkillsPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val lastCheckedInstalled: Flow<Instant?> = context.skillsDataStore.data.map { prefs ->
        prefs[KEY_LAST_CHECKED_INSTALLED]?.let(Instant::ofEpochMilli)
    }

    val lastCheckedBrowse: Flow<Instant?> = context.skillsDataStore.data.map { prefs ->
        prefs[KEY_LAST_CHECKED_BROWSE]?.let(Instant::ofEpochMilli)
    }

    /** Whether Browse leaves out the skills that are already installed. Off until chosen. */
    val hideInstalledInBrowse: Flow<Boolean> = context.skillsDataStore.data.map { prefs ->
        prefs[KEY_HIDE_INSTALLED_IN_BROWSE] ?: false
    }

    suspend fun setLastCheckedInstalled(instant: Instant) {
        context.skillsDataStore.edit { prefs ->
            prefs[KEY_LAST_CHECKED_INSTALLED] = instant.toEpochMilli()
        }
    }

    suspend fun setLastCheckedBrowse(instant: Instant) {
        context.skillsDataStore.edit { prefs ->
            prefs[KEY_LAST_CHECKED_BROWSE] = instant.toEpochMilli()
        }
    }

    suspend fun setHideInstalledInBrowse(hide: Boolean) {
        context.skillsDataStore.edit { prefs ->
            prefs[KEY_HIDE_INSTALLED_IN_BROWSE] = hide
        }
    }

    companion object {
        private val KEY_LAST_CHECKED_INSTALLED = longPreferencesKey("last_checked_installed")
        private val KEY_LAST_CHECKED_BROWSE = longPreferencesKey("last_checked_browse")
        private val KEY_HIDE_INSTALLED_IN_BROWSE = booleanPreferencesKey("hide_installed_in_browse")
    }
}
