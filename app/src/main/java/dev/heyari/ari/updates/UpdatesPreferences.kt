package dev.heyari.ari.updates

import android.content.Context
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

private val Context.updatesDataStore by preferencesDataStore(name = "ari_updates_prefs")

/**
 * DataStore-backed state that drives the in-app banner and the
 * inactive-user system-notification gate.
 *
 * Per category (model, skill) we persist a slim NSV-encoded payload
 * describing the pending updates plus two fingerprint slots:
 *   * `seenFingerprint` — what the user has acknowledged via the banner.
 *   * `notifiedFingerprint` — what we've already pushed a system
 *     notification for, so we don't re-fire on every worker cycle.
 *
 * `lastLaunchedAt` is the gate for "haven't opened the app in a while";
 * the worker only posts a system notification when the user has been
 * absent for [INACTIVE_THRESHOLD_DAYS] days or more.
 */
@Singleton
class UpdatesPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val lastLaunchedAt: Flow<Instant?> = context.updatesDataStore.data.map { prefs ->
        prefs[KEY_LAST_LAUNCHED_AT]?.let(Instant::ofEpochMilli)
    }

    fun pendingPayload(category: Category): Flow<String?> =
        context.updatesDataStore.data.map { prefs -> prefs[payloadKey(category)] }

    fun seenFingerprint(category: Category): Flow<String?> =
        context.updatesDataStore.data.map { prefs -> prefs[seenKey(category)] }

    fun notifiedFingerprint(category: Category): Flow<String?> =
        context.updatesDataStore.data.map { prefs -> prefs[notifiedKey(category)] }

    suspend fun setLastLaunchedAt(instant: Instant) {
        context.updatesDataStore.edit { it[KEY_LAST_LAUNCHED_AT] = instant.toEpochMilli() }
    }

    suspend fun setPendingPayload(category: Category, payload: String?) {
        context.updatesDataStore.edit { prefs ->
            val key = payloadKey(category)
            if (payload == null) prefs.remove(key) else prefs[key] = payload
        }
    }

    suspend fun setSeenFingerprint(category: Category, fingerprint: String?) {
        context.updatesDataStore.edit { prefs ->
            val key = seenKey(category)
            if (fingerprint == null) prefs.remove(key) else prefs[key] = fingerprint
        }
    }

    suspend fun setNotifiedFingerprint(category: Category, fingerprint: String?) {
        context.updatesDataStore.edit { prefs ->
            val key = notifiedKey(category)
            if (fingerprint == null) prefs.remove(key) else prefs[key] = fingerprint
        }
    }

    enum class Category { MODEL, SKILL }

    companion object {
        private val KEY_LAST_LAUNCHED_AT = longPreferencesKey("last_launched_at")

        private fun payloadKey(category: Category) =
            stringPreferencesKey("pending_payload_${category.name.lowercase()}")

        private fun seenKey(category: Category) =
            stringPreferencesKey("seen_fingerprint_${category.name.lowercase()}")

        private fun notifiedKey(category: Category) =
            stringPreferencesKey("notified_fingerprint_${category.name.lowercase()}")
    }
}
