package dev.heyari.ari.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SecretStore"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val PREFS_NAME = "ari_secrets"
private const val KEY_ALIAS = "ari_secret_store"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val IV_BYTES = 12

/** Every key the old EncryptedSharedPreferences store wrote its Tink keysets under. */
private const val LEGACY_KEYSET_PREFIX = "__androidx_security_crypto"

/**
 * Encrypted key-value store for sensitive config values (API keys, tokens).
 *
 * Values are sealed with AES-256-GCM under a key generated in — and never
 * leaving — the Android Keystore, and the ciphertext goes into an ordinary
 * private SharedPreferences file. That is the shape Google asked for when it
 * deprecated androidx.security.crypto wholesale in June 2025: "existing
 * platform APIs and direct use of Android Keystore". The old library still
 * works, but it is frozen, so every call into it warned forever.
 *
 * Two things are deliberately weaker than what it replaced, both worth knowing
 * before anyone treats this as a like-for-like swap:
 *
 * Preference *names* (`skillId_key`) are now in the clear. [allEntries] has to
 * read them back, which needs deterministic encryption, which is exactly what
 * went with the library. So somebody who can read the app's private data
 * directory learns which skills you have configured — not what you configured
 * them with. They could enumerate that from the rest of the data directory
 * anyway.
 *
 * There is no migration. The old store is cleared on first run rather than
 * re-encrypted, so saved credentials have to be entered once more.
 */
@Singleton
class SecretStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { discardLegacyStore(it) }

    // Resolved on first use, and `lazy` is synchronised by default — which
    // matters here, because two threads racing through [keystoreKey] would
    // both miss the existence check and the second would replace the key the
    // first had just encrypted under.
    private val key: SecretKey by lazy { keystoreKey() }

    fun get(skillId: String, key: String): String? =
        prefs.getString(prefKey(skillId, key), null)?.let { decrypt(it) }

    fun set(skillId: String, key: String, value: String?) {
        prefs.edit().apply {
            val pk = prefKey(skillId, key)
            if (value == null) remove(pk) else putString(pk, encrypt(value))
        }.apply()
    }

    /** Returns all stored secret entries as (skillId, key) → value. */
    fun allEntries(): Map<Pair<String, String>, String> {
        val result = mutableMapOf<Pair<String, String>, String>()
        for ((pk, stored) in prefs.all) {
            if (stored !is String) continue
            val parts = pk.split("_", limit = 2)
            if (parts.size != 2) continue
            val value = decrypt(stored) ?: continue
            result[parts[0] to parts[1]] = value
        }
        return result
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No IV is supplied on purpose. A Keystore AES-GCM key refuses one
        // outright, which is the point: it makes reusing an IV under the same
        // key something a caller cannot do by accident.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }

    /**
     * Null when a stored value cannot be opened — a restored backup, a Keystore
     * cleared by a lock-screen change, a truncated file. Callers read that as
     * "not set", which is the only honest answer available: the plaintext is
     * gone either way, and throwing here would take out whatever screen asked.
     */
    private fun decrypt(stored: String): String? = try {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        if (bytes.size <= IV_BYTES) {
            Log.w(TAG, "a stored secret was too short to hold an IV — ignoring it")
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, bytes, 0, IV_BYTES),
            )
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }
    } catch (e: GeneralSecurityException) {
        Log.w(TAG, "a stored secret could not be decrypted — ignoring it", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "a stored secret was not valid base64 — ignoring it", e)
        null
    }

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun prefKey(skillId: String, key: String) = "${skillId}_${key}"
}

/**
 * Empties the file if it still holds the previous EncryptedSharedPreferences
 * store, recognised by the Tink keysets that store kept beside the data.
 *
 * The same filename is reused rather than starting a new one so the backup and
 * data-extraction rules keep excluding a single path — miss that and the
 * user's credentials start riding to Google's servers. Nothing is carried
 * across: the old entries are encrypted under a Tink keyset this class has no
 * use for, and one user re-entering their API keys is cheaper than a migration
 * path that has to be right first time and is then dead code forever.
 *
 * `commit` rather than `apply` because the very next thing anyone does with
 * these preferences is read them.
 */
private fun discardLegacyStore(prefs: SharedPreferences) {
    if (prefs.all.keys.none { it.startsWith(LEGACY_KEYSET_PREFIX) }) return
    Log.i(TAG, "clearing the pre-Keystore secret store — saved credentials need re-entering")
    prefs.edit().clear().commit()
}
