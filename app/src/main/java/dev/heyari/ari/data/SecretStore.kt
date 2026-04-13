package dev.heyari.ari.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted key-value store for sensitive config values (API keys, tokens).
 * Backed by [EncryptedSharedPreferences] with AES-256 encryption, keyed
 * by the Android Keystore.
 */
@Singleton
class SecretStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "ari_secrets",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun get(skillId: String, key: String): String? =
        prefs.getString(prefKey(skillId, key), null)

    fun set(skillId: String, key: String, value: String?) {
        prefs.edit().apply {
            val pk = prefKey(skillId, key)
            if (value == null) remove(pk) else putString(pk, value)
        }.apply()
    }

    /** Returns all stored secret entries as (skillId, key) → value. */
    fun allEntries(): Map<Pair<String, String>, String> {
        val result = mutableMapOf<Pair<String, String>, String>()
        for ((pk, value) in prefs.all) {
            if (value !is String) continue
            val parts = pk.split("_", limit = 2)
            if (parts.size == 2) {
                result[parts[0] to parts[1]] = value
            }
        }
        return result
    }

    private fun prefKey(skillId: String, key: String) = "${skillId}_${key}"
}
