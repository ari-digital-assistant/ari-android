package dev.heyari.ari.ui.settings.skills

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.data.SkillsPreferences
import dev.heyari.ari.skills.SkillUpdateNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.heyari.ari.data.SecretStore
import uniffi.ari_ffi.AriEngine
import uniffi.ari_ffi.FfiBrowseEntry
import uniffi.ari_ffi.FfiConfigField
import uniffi.ari_ffi.FfiInstalledSkill
import uniffi.ari_ffi.FfiRegistryException
import uniffi.ari_ffi.FfiSkillManifest
import uniffi.ari_ffi.FfiSkillUpdate
import uniffi.ari_ffi.AssistantRegistry
import uniffi.ari_ffi.SkillRegistry
import java.io.File
import java.time.Instant
import javax.inject.Inject

/**
 * Backing state for the Skills settings screen.
 */
@HiltViewModel
class SkillsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val skillRegistry: SkillRegistry,
    private val engine: AriEngine,
    private val assistantRegistry: AssistantRegistry,
    private val notifier: SkillUpdateNotifier,
    private val prefs: SkillsPreferences,
    private val settingsRepository: dev.heyari.ari.data.SettingsRepository,
    private val secretStore: SecretStore,
    @dev.heyari.ari.di.ApplicationScope private val appScope: kotlinx.coroutines.CoroutineScope,
) : ViewModel() {

    private val skillsDirPath: String by lazy {
        File(context.filesDir, "skills").absolutePath
    }
    private val storageDirPath: String by lazy {
        File(context.filesDir, "skill-storage").absolutePath
    }

    private fun reloadEngineSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            engine.reloadCommunitySkills(skillsDirPath, storageDirPath)
            assistantRegistry.reloadCommunityAssistants()
        }
    }

    private val _state = MutableStateFlow(SkillsScreenState())
    val state: StateFlow<SkillsScreenState> = _state.asStateFlow()

    init {
        refresh()
        // Hydrate persisted "last checked" timestamps so the UI shows a
        // sensible value immediately on open instead of "Not yet checked".
        viewModelScope.launch {
            val installedAt = prefs.lastCheckedInstalled.first()
            val browseAt = prefs.lastCheckedBrowse.first()
            _state.update {
                it.copy(
                    lastCheckedInstalled = installedAt,
                    lastCheckedBrowse = browseAt,
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { skillRegistry.listInstalled() }
            _state.update { it.copy(installed = installed) }
        }
    }

    fun checkForUpdates() {
        _state.update { it.copy(checking = true, errorMessage = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { skillRegistry.checkForUpdates() }
            }
            val now = Instant.now()
            result.fold(
                onSuccess = { updates ->
                    prefs.setLastCheckedInstalled(now)
                    _state.update {
                        it.copy(
                            checking = false,
                            updates = updates,
                            lastCheckOk = true,
                            lastCheckedInstalled = now,
                        )
                    }
                    if (updates.isEmpty()) {
                        notifier.showOrUpdate(0)
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            checking = false,
                            errorMessage = friendlyError(e),
                            lastCheckOk = false,
                        )
                    }
                },
            )
        }
    }

    fun installUpdate(id: String) {
        _state.update { it.copy(installingIds = it.installingIds + id, errorMessage = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { skillRegistry.installSkillUpdate(id) }
            }
            _state.update { prev ->
                val newInstalling = prev.installingIds - id
                result.fold(
                    onSuccess = { installed ->
                        reloadEngineSkills()
                        prev.copy(
                            installingIds = newInstalling,
                            updates = prev.updates.filterNot { it.id == installed.id },
                            installed = prev.installed.replaceOrAppend(installed),
                        )
                    },
                    onFailure = { e ->
                        prev.copy(
                            installingIds = newInstalling,
                            errorMessage = friendlyError(e),
                        )
                    },
                )
            }
            if (_state.value.updates.isEmpty()) {
                notifier.showOrUpdate(0)
            }
        }
    }

    fun browse() {
        _state.update { it.copy(browsing = true, errorMessage = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { skillRegistry.browseRegistry() }
            }
            val now = Instant.now()
            result.fold(
                onSuccess = { entries ->
                    prefs.setLastCheckedBrowse(now)
                    _state.update {
                        it.copy(
                            browsing = false,
                            browse = entries,
                            lastBrowseOk = true,
                            lastCheckedBrowse = now,
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            browsing = false,
                            errorMessage = friendlyError(e),
                            lastBrowseOk = false,
                        )
                    }
                },
            )
        }
    }

    fun installById(id: String) {
        _state.update { it.copy(installingIds = it.installingIds + id, errorMessage = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { skillRegistry.installSkillById(id) }
            }
            _state.update { prev ->
                val newInstalling = prev.installingIds - id
                result.fold(
                    onSuccess = { installed ->
                        reloadEngineSkills()
                        prev.copy(
                            installingIds = newInstalling,
                            installed = prev.installed.replaceOrAppend(installed),
                            browse = prev.browse.map { row ->
                                if (row.id == installed.id) row.copy(installed = true) else row
                            },
                        )
                    },
                    onFailure = { e ->
                        prev.copy(
                            installingIds = newInstalling,
                            errorMessage = friendlyError(e),
                        )
                    },
                )
            }
        }
    }

    fun uninstall(id: String) {
        _state.update { it.copy(installingIds = it.installingIds + id, errorMessage = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { skillRegistry.uninstallSkillById(id) }
            }
            _state.update { prev ->
                val newInstalling = prev.installingIds - id
                result.fold(
                    onSuccess = {
                        reloadEngineSkills()
                        prev.copy(
                            installingIds = newInstalling,
                            installed = prev.installed.filterNot { it.id == id },
                            // Keep the row visible in Browse but flip `installed`
                            // off so the user sees their action land.
                            browse = prev.browse.map { row ->
                                if (row.id == id) row.copy(installed = false) else row
                            },
                            updates = prev.updates.filterNot { it.id == id },
                        )
                    },
                    onFailure = { e ->
                        prev.copy(
                            installingIds = newInstalling,
                            errorMessage = friendlyError(e),
                        )
                    },
                )
            }
        }
    }

    fun setBrowseQuery(query: String) {
        _state.update { it.copy(browseQuery = query) }
    }

    /**
     * Populate [SkillsScreenState.detailManifest] for an installed skill
     * by reading the on-disk `SKILL.md`.
     */
    fun loadInstalledManifest(id: String) {
        _state.update { it.copy(detailManifest = null, detailManifestLoading = true) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { skillRegistry.readInstalledManifest(id) }
            }
            _state.update { prev ->
                result.fold(
                    onSuccess = { manifest ->
                        prev.copy(detailManifest = manifest, detailManifestLoading = false)
                    },
                    onFailure = { e ->
                        prev.copy(
                            detailManifest = null,
                            detailManifestLoading = false,
                            errorMessage = friendlyError(e),
                        )
                    },
                )
            }
        }
    }

    /**
     * Populate [SkillsScreenState.detailManifest] for a not-yet-installed
     * skill by downloading the registry's preview SKILL.md sidecar. Lets
     * the browse → detail view show the full author/homepage/capabilities
     * and full markdown body before the user decides to install.
     *
     * Silently no-ops (rather than surfacing an error) when the registry
     * doesn't carry a sidecar for this skill — the detail screen then
     * falls back to the browse-entry fields, which is still useful.
     */
    fun loadBrowseManifestPreview(id: String) {
        _state.update { it.copy(detailManifest = null, detailManifestLoading = true) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { skillRegistry.fetchManifestPreview(id) }
            }
            _state.update { prev ->
                result.fold(
                    onSuccess = { manifest ->
                        prev.copy(detailManifest = manifest, detailManifestLoading = false)
                    },
                    onFailure = { e ->
                        // No-sidecar is expected for older registry entries —
                        // drop the spinner but don't bark at the user.
                        if (e is FfiRegistryException.ManifestUnavailable) {
                            prev.copy(detailManifest = null, detailManifestLoading = false)
                        } else {
                            prev.copy(
                                detailManifest = null,
                                detailManifestLoading = false,
                                errorMessage = friendlyError(e),
                            )
                        }
                    },
                )
            }
        }
    }

    fun clearDetailManifest() {
        _state.update { it.copy(detailManifest = null, detailManifestLoading = false) }
    }

    /**
     * Hydrate persistent settings (DataStore for non-secrets,
     * EncryptedSharedPreferences for secrets) into the in-memory
     * [uniffi.ari_ffi.SkillSettingsStore], then publish the resulting
     * field list to [SkillsScreenState.detailSettings] for the detail
     * screen to render.
     *
     * Hydration on every open is a small inefficiency we accept so the
     * settings panel always reflects on-disk truth — the alternative is
     * a startup-wide rehydrate, which the codebase doesn't currently do
     * for non-secret config (only secrets) and which is a separate piece
     * of work to land properly.
     */
    fun loadSkillSettings(skillId: String) {
        _state.update { it.copy(detailSettings = emptyList(), detailSettingsLoading = true) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    // First pass: read the schema (currentValue may be empty
                    // if the in-memory store hasn't been hydrated yet).
                    val schema = skillRegistry.getSkillSettings(skillId)
                    // Push persisted values into the shared in-memory store
                    // for any field that's still empty. We treat missing
                    // currentValue as "not yet hydrated" — works because the
                    // store is process-wide and persists for the app's
                    // lifetime, so the second visit is a no-op.
                    for (field in schema) {
                        if (field.currentValue != null) continue
                        val persisted = if (field.fieldType == "secret") {
                            secretStore.get(skillId, field.key)
                        } else {
                            settingsRepository.assistantConfigValue(skillId, field.key).first()
                        }
                        if (persisted != null) {
                            skillRegistry.setSkillSetting(skillId, field.key, persisted)
                        }
                    }
                    // Second pass: re-read so currentValue reflects any
                    // values we just hydrated.
                    skillRegistry.getSkillSettings(skillId)
                }
            }
            _state.update { prev ->
                result.fold(
                    onSuccess = { fields ->
                        prev.copy(detailSettings = fields, detailSettingsLoading = false)
                    },
                    onFailure = { e ->
                        prev.copy(
                            detailSettings = emptyList(),
                            detailSettingsLoading = false,
                            errorMessage = friendlyError(e),
                        )
                    },
                )
            }
        }
    }

    /**
     * Persist a setting change. Writes to:
     *   1. The shared in-memory FFI store (so the engine sees the change
     *      on the next outbound API call without needing a restart).
     *   2. SecretStore (encrypted) for secrets, with the DataStore copy
     *      cleared as a belt-and-braces precaution.
     *   3. SettingsRepository / DataStore for non-secrets.
     *
     * Re-fetches the settings after writing so the UI reflects the new
     * `currentValue` (e.g. the `••••••••` placeholder appearing for
     * secrets the user just typed in).
     */
    fun setSkillSetting(skillId: String, key: String, value: String, isSecret: Boolean) {
        // Persistence runs on the process-wide ApplicationScope, NOT
        // viewModelScope. The common trigger for this method is the
        // SkillSettingsPanel field's onDispose flush — fired exactly as
        // the user pops back from the detail screen, which also clears
        // this VM's viewModelScope. A coroutine launched into a scope
        // that's about to be cancelled never gets to do its work, so
        // any "type API key, press back" flow would silently lose the
        // value. Persisting on a longer-lived scope fixes that without
        // having to run blocking writes on the dispose thread.
        appScope.launch {
            skillRegistry.setSkillSetting(skillId, key, value)
            if (isSecret) {
                secretStore.set(skillId, key, value)
                // Belt-and-braces: a previous build may have written a
                // secret-typed field into DataStore before SecretStore
                // existed. Wipe it so we never read a stale plaintext.
                settingsRepository.setAssistantConfigValue(skillId, key, null)
            } else {
                settingsRepository.setAssistantConfigValue(skillId, key, value)
            }
            // Reflect the write in this VM's state if it's still alive.
            // Safe to touch _state from any scope — MutableStateFlow is
            // thread-safe.
            val refreshed =
                runCatching { skillRegistry.getSkillSettings(skillId) }.getOrDefault(emptyList())
            _state.update { it.copy(detailSettings = refreshed) }
        }
    }

    fun clearSkillSettings() {
        _state.update { it.copy(detailSettings = emptyList(), detailSettingsLoading = false) }
    }

    private fun friendlyError(t: Throwable): String = when (t) {
        is FfiRegistryException.Registry -> "Couldn't reach the registry — check your connection."
        is FfiRegistryException.Store -> "Local skill store error: ${t.message ?: "unknown"}"
        is FfiRegistryException.NotFound -> "The registry no longer has that skill."
        is FfiRegistryException.NotInstalled -> "That skill isn't installed."
        is FfiRegistryException.Manifest -> "Couldn't read the skill manifest."
        is FfiRegistryException.ManifestUnavailable ->
            "The registry has no preview for that skill yet."
        is FfiRegistryException.TrustKey -> "Signing key error — reinstall Ari."
        else -> t.message ?: "Something went wrong."
    }
}

data class SkillsScreenState(
    val installed: List<FfiInstalledSkill> = emptyList(),
    val updates: List<FfiSkillUpdate> = emptyList(),
    val browse: List<FfiBrowseEntry> = emptyList(),
    val checking: Boolean = false,
    val browsing: Boolean = false,
    val installingIds: Set<String> = emptySet(),
    val lastCheckOk: Boolean? = null,
    val lastBrowseOk: Boolean? = null,
    val errorMessage: String? = null,
    val browseQuery: String = "",
    val lastCheckedInstalled: Instant? = null,
    val lastCheckedBrowse: Instant? = null,
    val detailManifest: FfiSkillManifest? = null,
    val detailManifestLoading: Boolean = false,
    /// Schema + current values for the active skill's user-configurable
    /// settings. Empty for skills that declare no settings, or while
    /// the load is in flight.
    val detailSettings: List<FfiConfigField> = emptyList(),
    val detailSettingsLoading: Boolean = false,
)

private fun List<FfiInstalledSkill>.replaceOrAppend(skill: FfiInstalledSkill): List<FfiInstalledSkill> {
    val idx = indexOfFirst { it.id == skill.id }
    return if (idx >= 0) toMutableList().also { it[idx] = skill } else this + skill
}
