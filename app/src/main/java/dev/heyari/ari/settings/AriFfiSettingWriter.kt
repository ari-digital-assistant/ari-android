package dev.heyari.ari.settings

import dev.heyari.ari.data.SecretStore
import dev.heyari.ari.data.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import uniffi.ari_ffi.FfiSettingWriter
import uniffi.ari_ffi.SkillSettingsStore

/**
 * Engine-driven persistence of a skill's own settings (`ari::setting_set`).
 *
 * The engine passes `isSecret` (derived from the manifest field type), so we
 * route to encrypted vs plain storage WITHOUT calling back into the engine —
 * critical, because this runs on the thread that holds the engine mutex during
 * `settingsAction`, so any re-entrant engine call would deadlock.
 *
 * Writes are synchronous: the engine thread blocks until they complete. The
 * routing logic lives in [route] so it can be exercised by a plain JVM test
 * with fakes (no Android Context, no real EncryptedSharedPreferences /
 * DataStore).
 */
@Singleton
class AriFfiSettingWriter @Inject constructor(
    private val secretStore: SecretStore,
    private val settingsRepository: SettingsRepository,
    private val skillSettingsStore: SkillSettingsStore,
) : FfiSettingWriter {

    override fun setValue(
        skillId: String,
        key: String,
        value: String,
        isSecret: Boolean,
    ): Boolean = route(
        skillId = skillId,
        key = key,
        value = value,
        isSecret = isSecret,
        persistSecret = { s, k, v ->
            secretStore.set(s, k, v)
            // Belt-and-braces: a previous build may have written a
            // secret-typed field into DataStore as plaintext. Wipe it so we
            // never read a stale, unencrypted copy on the next setting_get.
            runBlocking { settingsRepository.setAssistantConfigValue(s, k, null) }
        },
        persistPlain = { s, k, v ->
            runBlocking { settingsRepository.setAssistantConfigValue(s, k, v) }
        },
        updateMirror = { s, k, v -> skillSettingsStore.setValue(s, k, v) },
    )

    companion object {
        /**
         * Pure routing: update the in-memory mirror, then persist to the
         * encrypted store (secret) or DataStore (plain). Returns `false` if any
         * write throws, so the engine sees the failure rather than a deadlock
         * or crash. The three persistence side-effects are injected so this can
         * be unit-tested with fakes.
         */
        internal fun route(
            skillId: String,
            key: String,
            value: String,
            isSecret: Boolean,
            persistSecret: (skillId: String, key: String, value: String) -> Unit,
            persistPlain: (skillId: String, key: String, value: String) -> Unit,
            updateMirror: (skillId: String, key: String, value: String) -> Unit,
        ): Boolean = try {
            updateMirror(skillId, key, value)
            if (isSecret) persistSecret(skillId, key, value) else persistPlain(skillId, key, value)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
