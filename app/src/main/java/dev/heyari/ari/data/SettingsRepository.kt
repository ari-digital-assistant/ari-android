package dev.heyari.ari.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.locale.SupportedLocales
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val ASSISTANT_CONFIG_PREFIX = "assistant_config_"

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
     * Whether the user may interrupt Ari mid-sentence during a "Let's talk"
     * conversation. Default on — it's the natural feel of a conversation.
     * Effective barge-in additionally requires a hardware echo canceller
     * (see VoiceSession); when unavailable, talk mode stays turn-based.
     */
    val bargeInEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BARGE_IN_ENABLED] ?: true
    }

    suspend fun setBargeInEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BARGE_IN_ENABLED] = enabled
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

    /**
     * Snapshot of every `assistant_config_*` entry currently on disk,
     * decoded back into `(skillId, key, value)` triples. Used at app
     * startup to rehydrate the in-memory `SkillSettingsStore` so a
     * skill reading `ari::setting_get` sees the user's chosen
     * defaults without first having to visit the skill's settings
     * page.
     *
     * The DataStore key format `assistant_config_<skillId>_<fieldKey>`
     * has no unambiguous separator, so we rely on the convention that
     * skill IDs are reverse-DNS (dots, no underscores) and field keys
     * are snake_case (underscores, no dots). The first underscore after
     * the `assistant_config_` prefix marks the boundary. A skill author
     * who introduces an underscore into their reverse-DNS id would
     * break this; no enforcement exists today.
     */
    suspend fun allAssistantConfigEntries(): List<AssistantConfigEntry> {
        val prefs = context.dataStore.data.first()
        return prefs.asMap().entries.mapNotNull { (dsKey, rawValue) ->
            val name = dsKey.name
            if (!name.startsWith(ASSISTANT_CONFIG_PREFIX) || rawValue !is String) return@mapNotNull null
            val remainder = name.removePrefix(ASSISTANT_CONFIG_PREFIX)
            val boundary = remainder.indexOf('_')
            if (boundary <= 0 || boundary >= remainder.length - 1) return@mapNotNull null
            AssistantConfigEntry(
                skillId = remainder.substring(0, boundary),
                key = remainder.substring(boundary + 1),
                value = rawValue,
            )
        }
    }

    data class AssistantConfigEntry(val skillId: String, val key: String, val value: String)

    /** Whether the first-run onboarding wizard has been completed (or skipped). */
    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETED] = completed
        }
    }

    /** Whether the FunctionGemma skill router is enabled. */
    val routerEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ROUTER_ENABLED] ?: true
    }

    suspend fun setRouterEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ROUTER_ENABLED] = enabled
        }
    }

    /** The user's chosen TTS voice name, or null for system default. */
    val activeTtsVoice: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_TTS_VOICE]
    }

    suspend fun setActiveTtsVoice(name: String?) {
        context.dataStore.edit { prefs ->
            if (name == null) prefs.remove(KEY_ACTIVE_TTS_VOICE)
            else prefs[KEY_ACTIVE_TTS_VOICE] = name
        }
    }

    /**
     * The user's currently-selected language, ISO 639-1 lowercase.
     * Defaults to the system language if Ari supports it, otherwise
     * `"en"`. The frontend is the single source of truth for locale;
     * the engine and skills read through here via the
     * `FfiLocaleProvider` host capability.
     *
     * Default is computed on every read rather than stored on first
     * launch, so a user who changes their phone's system language
     * before the onboarding wizard runs sees the right starting value.
     * Once they pick explicitly, [setActiveLocale] writes it through.
     */
    val activeLocale: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_LOCALE] ?: SupportedLocales.defaultFromSystem()
    }

    suspend fun setActiveLocale(code: String) {
        require(SupportedLocales.isSupported(code)) {
            "Unsupported locale: $code (supported: ${SupportedLocales.codes})"
        }
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_LOCALE] = code
        }
    }

    /**
     * Set to `true` when the onboarding wizard finishes with the user
     * having picked the Cloud assistant option. Cleared once they
     * actually install (and activate) a cloud assistant skill — at
     * that point they have what they wanted, so the conversation-
     * screen "you still need to install one" hint should disappear.
     *
     * Lives here rather than in OnboardingViewModel because it has
     * to outlive the wizard's lifecycle — we still want to nag the
     * user days later if they exited the wizard without finishing
     * the install step.
     */
    val pendingCloudAssistantSetup: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PENDING_CLOUD_ASSISTANT_SETUP] ?: false
    }

    suspend fun setPendingCloudAssistantSetup(pending: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PENDING_CLOUD_ASSISTANT_SETUP] = pending
        }
    }

    /**
     * Whether to route non-English transcription through the user's cloud
     * assistant instead of the on-device Whisper-turbo model. Off by
     * default; only meaningful when a cloud assistant is configured.
     *
     * Recorded here as a stable user preference; the actual cloud-STT
     * call path (when a cloud assistant supports STT) reads this flag
     * before deciding which transcriber to invoke.
     */
    val cloudSttForNonEnglish: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLOUD_STT_FOR_NON_ENGLISH] ?: false
    }

    suspend fun setCloudSttForNonEnglish(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CLOUD_STT_FOR_NON_ENGLISH] = enabled
        }
    }

    companion object {
        private val KEY_ACTIVE_STT_MODEL = stringPreferencesKey("active_stt_model")
        private val KEY_ACTIVE_WAKE_WORD = stringPreferencesKey("active_wake_word")
        private val KEY_WAKE_WORD_SENSITIVITY = stringPreferencesKey("wake_word_sensitivity")
        private val KEY_ACTIVE_LLM_MODEL = stringPreferencesKey("active_llm_model")
        private val KEY_ACTIVE_ASSISTANT = stringPreferencesKey("active_assistant")
        private val KEY_ACTIVE_TTS_VOICE = stringPreferencesKey("active_tts_voice")
        private val KEY_ACTIVE_LOCALE = stringPreferencesKey("active_locale")
        private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        private val KEY_BARGE_IN_ENABLED = booleanPreferencesKey("barge_in_enabled")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_ROUTER_ENABLED = booleanPreferencesKey("router_enabled")
        private val KEY_CLOUD_STT_FOR_NON_ENGLISH = booleanPreferencesKey("cloud_stt_for_non_english")
        private val KEY_PENDING_CLOUD_ASSISTANT_SETUP = booleanPreferencesKey("pending_cloud_assistant_setup")
    }
}
