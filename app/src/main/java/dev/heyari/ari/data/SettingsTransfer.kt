package dev.heyari.ari.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.MainActivity
import dev.heyari.ari.di.EngineModule
import dev.heyari.ari.llm.LlmDownloadManager
import dev.heyari.ari.llm.LlmModelRegistry
import dev.heyari.ari.models.ModelUpdateWorker
import dev.heyari.ari.stt.ModelDownloadManager
import dev.heyari.ari.stt.SttMode
import dev.heyari.ari.stt.SttModelRegistry
import dev.heyari.ari.wakeword.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import uniffi.ari_ffi.SkillRegistry
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SettingsTransfer"
private const val EXPORT_FORMAT = "ari-settings"
private const val EXPORT_VERSION = 1

/** What an import did, or why it couldn't. */
sealed interface ImportOutcome {
    /**
     * Settings are in place. [failedSkills] are the ids that would not
     * install; [downloads] are the display-name string resources of the models
     * now downloading in the background.
     */
    data class Imported(
        val failedSkills: List<String>,
        val downloads: List<Int>,
    ) : ImportOutcome

    data object NotAnExport : ImportOutcome, ParsedExport

    /** Written by a later Ari, in a format this one doesn't know. */
    data object FromNewerAri : ImportOutcome, ParsedExport
}

sealed interface ParsedExport

/**
 * A settings export. [settings] maps stored names to String or Boolean values,
 * see [SettingsRepository.exportedSettings]. The auto-update choices are null
 * when the file leaves them out.
 */
data class SettingsExport(
    val settings: Map<String, Any>,
    val autoUpdate: Boolean?,
    val autoUpdateOnMetered: Boolean?,
    val skills: List<String>,
) : ParsedExport

internal fun encodeExport(export: SettingsExport): String {
    val settings = JSONObject()
    export.settings.toSortedMap().forEach { (name, value) -> settings.put(name, value) }
    val autoUpdate = JSONObject()
    export.autoUpdate?.let { autoUpdate.put("enabled", it) }
    export.autoUpdateOnMetered?.let { autoUpdate.put("allow_metered", it) }
    return JSONObject()
        .put("format", EXPORT_FORMAT)
        .put("version", EXPORT_VERSION)
        .put("settings", settings)
        .put("auto_update", autoUpdate)
        .put("skills", JSONArray(export.skills.sorted()))
        .toString(2)
}

internal fun parseExport(text: String): ParsedExport {
    val root = try {
        JSONObject(text)
    } catch (e: JSONException) {
        return ImportOutcome.NotAnExport
    }
    if (root.optString("format") != EXPORT_FORMAT) return ImportOutcome.NotAnExport
    val version = root.optInt("version")
    if (version < 1) return ImportOutcome.NotAnExport
    if (version > EXPORT_VERSION) return ImportOutcome.FromNewerAri

    val settingsJson = root.optJSONObject("settings") ?: JSONObject()
    val settings = settingsJson.keys().asSequence()
        .associateWith { settingsJson.get(it) }
        .filterValues { it is String || it is Boolean }
    val autoUpdate = root.optJSONObject("auto_update") ?: JSONObject()
    val skillsJson = root.optJSONArray("skills") ?: JSONArray()
    val skills = (0 until skillsJson.length()).mapNotNull { skillsJson.opt(it) as? String }
    return SettingsExport(
        settings = settings,
        autoUpdate = autoUpdate.opt("enabled") as? Boolean,
        autoUpdateOnMetered = autoUpdate.opt("allow_metered") as? Boolean,
        skills = skills,
    )
}

/**
 * Writes the user's settings and installed skills to a JSON export, and puts
 * them back from one. API keys and sign-ins never go in the file, and neither
 * does anything that belongs to this phone; see [SettingsRepository.exportedSettings].
 */
@Singleton
class SettingsTransfer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val autoUpdatePreferences: AutoUpdatePreferences,
    private val skillRegistry: SkillRegistry,
    private val sttDownloads: ModelDownloadManager,
    private val llmDownloads: LlmDownloadManager,
) {
    suspend fun export(): String = withContext(Dispatchers.IO) {
        val skills = skillRegistry.listInstalled().map { it.id }
        encodeExport(
            SettingsExport(
                settings = settingsRepository.exportedSettings(secretFields(skills)),
                autoUpdate = autoUpdatePreferences.enabled.first(),
                autoUpdateOnMetered = autoUpdatePreferences.allowMetered.first(),
                skills = skills,
            ),
        )
    }

    /**
     * Install the file's skills, replace the settings, and start downloading
     * the models they use. Ari needs a [restartAri] afterwards: the engine and
     * the listening service read much of this once, at start.
     *
     * Skills go first because the settings name them (the active assistant,
     * each skill's settings). One that fails to install doesn't stop the rest:
     * the settings still go in, and the outcome says which to retry.
     */
    suspend fun import(text: String): ImportOutcome = withContext(Dispatchers.IO) {
        val export = when (val parsed = parseExport(text)) {
            is SettingsExport -> parsed
            is ImportOutcome.NotAnExport -> return@withContext parsed
            is ImportOutcome.FromNewerAri -> return@withContext parsed
        }

        val installed = skillRegistry.listInstalled().mapTo(HashSet()) { it.id }
        val failedSkills = export.skills.filter { it !in installed }.filter { id ->
            runCatching { skillRegistry.installSkillById(id) }
                .onFailure { Log.w(TAG, "could not install $id", it) }
                .isFailure
        }

        settingsRepository.replaceExportedSettings(export.settings)
        export.autoUpdate?.let { autoUpdatePreferences.setEnabled(it) }
        export.autoUpdateOnMetered?.let { autoUpdatePreferences.setAllowMetered(it) }
        // A cold start only schedules the update check when none is pending,
        // so a changed metered choice would never reach its network constraint.
        if (autoUpdatePreferences.enabled.first()) {
            ModelUpdateWorker.schedule(
                context,
                allowMetered = autoUpdatePreferences.allowMetered.first(),
                replace = true,
            )
        }

        ImportOutcome.Imported(failedSkills = failedSkills, downloads = startModelDownloads())
    }

    /**
     * Skill settings typed as secrets, as (skillId, key). A skill whose schema
     * can't be read has all of its settings counted, since nothing says which
     * of them are secret.
     */
    private suspend fun secretFields(skills: List<String>): Set<Pair<String, String>> {
        val locale = settingsRepository.activeLocale.first()
        val allConfig = settingsRepository.allAssistantConfigEntries()
        return skills.flatMapTo(HashSet()) { id ->
            runCatching { skillRegistry.getSkillSettings(id, locale) }.fold(
                onSuccess = { fields -> fields.filter { it.fieldType == "secret" }.map { id to it.key } },
                onFailure = { e ->
                    Log.w(TAG, "no settings schema for $id; leaving its settings out", e)
                    allConfig.filter { it.skillId == id }.map { id to it.key }
                },
            )
        }
    }

    /**
     * Start the downloads the imported settings need: the on-device speech
     * model when transcription is on-device, the built-in assistant's model
     * when that is the active assistant. Returns their display names.
     */
    private suspend fun startModelDownloads(): List<Int> {
        val started = mutableListOf<Int>()
        if (settingsRepository.sttMode.first() == SttMode.ON_DEVICE) {
            val stt = SttModelRegistry.onDeviceFor(
                settingsRepository.activeLocale.first(),
                SttModelRegistry.byId(settingsRepository.activeSttModelId.first()),
            )
            settingsRepository.setActiveSttModelId(stt.id)
            if (!sttDownloads.isDownloaded(stt)) {
                sttDownloads.download(stt)
                started += stt.displayNameRes
            }
        }
        if (settingsRepository.activeAssistantId.first() == EngineModule.BUILTIN_ASSISTANT_ID) {
            val llm = LlmModelRegistry.byId(settingsRepository.activeLlmModelId.first())
            if (llm != null && !llmDownloads.isDownloaded(llm)) {
                llmDownloads.download(llm)
                started += llm.displayNameRes
            }
        }
        return started
    }
}

/**
 * Relaunch Ari in a fresh process, so everything that reads settings once at
 * start reads the imported ones. Model downloads carry on: WorkManager owns them.
 *
 * The listening service is stopped first. Left running, Android would restart
 * it on its own once the process died, from the background, where a
 * microphone service is refused; the relaunched activity starts it instead.
 */
fun restartAri(context: Context) {
    context.stopService(Intent(context, WakeWordService::class.java))
    context.startActivity(Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java)))
    Runtime.getRuntime().exit(0)
}
