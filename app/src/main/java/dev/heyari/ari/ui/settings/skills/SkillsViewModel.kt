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
import uniffi.ari_ffi.AriEngine
import uniffi.ari_ffi.FfiBrowseEntry
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
     * Populate [SkillsScreenState.detailManifest] for an installed skill.
     * Browse-only skills can't use this — the manifest only exists on disk
     * once the skill's actually been installed. The UI falls back to
     * `FfiBrowseEntry` fields for browse-tab detail views.
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

    fun clearDetailManifest() {
        _state.update { it.copy(detailManifest = null, detailManifestLoading = false) }
    }

    private fun friendlyError(t: Throwable): String = when (t) {
        is FfiRegistryException.Registry -> "Couldn't reach the registry — check your connection."
        is FfiRegistryException.Store -> "Local skill store error: ${t.message ?: "unknown"}"
        is FfiRegistryException.NotFound -> "The registry no longer has that skill."
        is FfiRegistryException.NotInstalled -> "That skill isn't installed."
        is FfiRegistryException.Manifest -> "Couldn't read the skill manifest."
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
)

private fun List<FfiInstalledSkill>.replaceOrAppend(skill: FfiInstalledSkill): List<FfiInstalledSkill> {
    val idx = indexOfFirst { it.id == skill.id }
    return if (idx >= 0) toMutableList().also { it[idx] = skill } else this + skill
}
