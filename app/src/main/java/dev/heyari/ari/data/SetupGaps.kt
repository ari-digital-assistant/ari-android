package dev.heyari.ari.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.listening.ListeningCondition
import dev.heyari.ari.listening.ListeningMode
import dev.heyari.ari.listening.PlaceGeofences
import dev.heyari.ari.listening.playServicesInstalled
import dev.heyari.ari.stt.CloudTranscriber
import dev.heyari.ari.stt.SttMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import uniffi.ari_ffi.FfiConfigField
import uniffi.ari_ffi.SkillRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Setup the settings rely on but this phone doesn't have yet: a grant or a
 * key that no settings import or backup restore can carry. Each one fails
 * quietly at the moment it's needed, so the home screen asks for it up front.
 */
data class SetupGaps(
    /** Custom listening uses places, places exist, and location isn't granted. */
    val placesNeedLocation: Boolean = false,
    /** The active assistant, when a key it requires is missing. */
    val assistantNeedingKey: SkillNeedingKey? = null,
    /** Transcription is set to OpenAI and has no key. */
    val speechNeedsKey: Boolean = false,
)

data class SkillNeedingKey(val skillId: String, val name: String)

@Singleton
class SetupGapFinder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val skillRegistry: SkillRegistry,
    private val secretStore: SecretStore,
    private val placeGeofences: PlaceGeofences,
) {
    suspend fun find(): SetupGaps = withContext(Dispatchers.IO) {
        SetupGaps(
            placesNeedLocation = placesNeedLocation(),
            assistantNeedingKey = settingsRepository.activeAssistantId.first()?.let { assistantNeedingKey(it) },
            speechNeedsKey = settingsRepository.sttMode.first() == SttMode.OPENAI &&
                secretStore.get(CloudTranscriber.SECRET_SCOPE, CloudTranscriber.SECRET_KEY).isNullOrBlank(),
        )
    }

    private suspend fun placesNeedLocation(): Boolean =
        settingsRepository.listeningMode.first() == ListeningMode.CUSTOM &&
            ListeningCondition.PLACE in settingsRepository.listeningConditions.first() &&
            settingsRepository.listeningPlacesOnce().isNotEmpty() &&
            // Without Play Services there are no geofences to grant location
            // for, and the Places page already says so.
            playServicesInstalled(context) &&
            !placeGeofences.hasPermissions()

    private suspend fun assistantNeedingKey(skillId: String): SkillNeedingKey? {
        val locale = settingsRepository.activeLocale.first()
        // The built-in assistant has no settings to read, and an assistant
        // that failed to install has none either; neither needs a key here.
        val fields = runCatching { skillRegistry.getSkillSettings(skillId, locale) }.getOrNull() ?: return null
        val byKey = fields.associateBy { it.key }
        val missing = fields.any { field ->
            field.fieldType == "secret" && field.required && isShown(skillId, field, byKey) &&
                secretStore.get(skillId, field.key).isNullOrBlank()
        }
        if (!missing) return null
        val name = runCatching { skillRegistry.readInstalledManifest(skillId, locale).name }.getOrDefault(skillId)
        return SkillNeedingKey(skillId, name)
    }

    /**
     * The settings panel's `show_when` rule, read from what's stored: the
     * registry's own current values may not be loaded yet this early.
     */
    private suspend fun isShown(skillId: String, field: FfiConfigField, byKey: Map<String, FfiConfigField>): Boolean {
        val controllerKey = field.showWhenKey ?: return true
        val controller = byKey[controllerKey] ?: return false
        val value = settingsRepository.assistantConfigValue(skillId, controllerKey).first()
            ?: controller.currentValue
            ?: controller.defaultValue
        return value in field.showWhenEquals
    }
}
