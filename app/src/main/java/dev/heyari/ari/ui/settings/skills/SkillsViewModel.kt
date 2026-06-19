package dev.heyari.ari.ui.settings.skills

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.annotation.StringRes
import dev.heyari.ari.R
import dev.heyari.ari.data.SkillsPreferences
import dev.heyari.ari.skills.SkillUpdateNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.heyari.ari.data.SecretStore
import dev.heyari.ari.di.EngineHolder
import uniffi.ari_ffi.FfiBrowseEntry
import uniffi.ari_ffi.FfiConfigField
import uniffi.ari_ffi.FfiInstalledSkill
import uniffi.ari_ffi.FfiRegistryException
import uniffi.ari_ffi.FfiSettingsQueryResult
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
    private val engineHolder: EngineHolder,
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
            reloadEngineSkillsBlocking()
        }
    }

    /**
     * Synchronous version of `reloadEngineSkills` — callers that need
     * the registry to be queryable immediately after install (e.g. the
     * post-install assistant prompt detection) call this from a
     * coroutine and `await` it before checking `listAssistants()`.
     * Otherwise the registry's still empty when we look.
     */
    private suspend fun reloadEngineSkillsBlocking() {
        withContext(Dispatchers.IO) {
            val engine = engineHolder.engine()
            engine.reloadCommunitySkills(skillsDirPath, storageDirPath)
            // reloadAndApply re-scans the community assistant directory
            // AND re-pushes the (active + named) assistant state into
            // the engine. Without the apply step, a freshly-installed
            // cloud assistant's aliases wouldn't reach the engine until
            // the user next touched assistant Settings — meaning "ask
            // <new alias> X" would silently fall through to the active
            // fallback.
            assistantRegistry.reloadAndApply(engine)
        }
    }

    private val _state = MutableStateFlow(SkillsScreenState())
    val state: StateFlow<SkillsScreenState> = _state.asStateFlow()

    /**
     * Tracks the in-flight detail-manifest load (either installed or
     * browse-preview). The detail screen's `LaunchedEffect(skillId,
     * isInstalledLocally)` can fire BOTH paths in quick succession when
     * `isInstalledLocally` flips from false→true after `state.installed`
     * loads — and the slow browse-preview HTTP fetch can land *after*
     * the fast on-disk installed-manifest read, overwriting the Italian
     * description with the English-only registry sidecar. We serialise
     * by cancelling whichever job was previously launched before
     * starting a new one.
     */
    private var detailManifestJob: Job? = null

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
        // Mirror the user's active language into screen state so the browse
        // list can flag locale-matched skills and the detail screen can
        // warn before installing a skill that doesn't speak our language.
        viewModelScope.launch {
            settingsRepository.activeLocale.collect { code ->
                _state.update { it.copy(activeLocale = code) }
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
            // Run the post-install assistant detection BEFORE the state
            // update so the prompt fields land in the same atomic
            // transition as the install completion. The registry
            // reload must complete *synchronously* — the previous
            // fire-and-forget version raced the listAssistants() call
            // and `match` always came back null on first install.
            val (promptId, promptName) = result.fold(
                onSuccess = { installed ->
                    reloadEngineSkillsBlocking()
                    val active = settingsRepository.activeAssistantId.first()
                    // Offer to make the just-installed skill the default
                    // assistant when the user hasn't deliberately picked a
                    // *real* one yet. Gating on `active == null` alone missed
                    // the common case: onboarding leaves the built-in local
                    // assistant active as the working default (and choosing
                    // "Cloud" doesn't change that), so `active` is non-null
                    // and the prompt never fired — even for a user who set
                    // out specifically to add a cloud assistant. Treat the
                    // built-in default the same as "nothing chosen", and
                    // don't prompt if the installed skill is already active.
                    val onlyDefaultActive =
                        active == null || active == dev.heyari.ari.di.EngineModule.BUILTIN_ASSISTANT_ID
                    val match = if (onlyDefaultActive && installed.id != active) {
                        assistantRegistry.listAssistants().firstOrNull { it.id == installed.id }
                    } else {
                        null
                    }
                    if (match != null) match.id to match.name else null to ""
                },
                onFailure = { null to "" },
            )
            _state.update { prev ->
                val newInstalling = prev.installingIds - id
                result.fold(
                    onSuccess = { installed ->
                        prev.copy(
                            installingIds = newInstalling,
                            installed = prev.installed.replaceOrAppend(installed),
                            browse = prev.browse.map { row ->
                                if (row.id == installed.id) row.copy(installed = true) else row
                            },
                            // Only overwrite the prompt fields when this
                            // install qualifies — preserves a prior
                            // pending prompt if the user races two
                            // installs (unlikely but harmless).
                            pendingAssistantPromptId = promptId
                                ?: prev.pendingAssistantPromptId,
                            pendingAssistantPromptName = if (promptId != null) {
                                promptName
                            } else {
                                prev.pendingAssistantPromptName
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

    /**
     * User tapped "Set as default" on the post-install prompt.
     * Activates the assistant via the same path
     * `SettingsViewModel.selectAssistant` uses (engine apply + flag
     * cleanup), then clears the prompt fields.
     */
    fun confirmPendingAssistantPrompt() {
        val id = _state.value.pendingAssistantPromptId ?: return
        viewModelScope.launch {
            settingsRepository.setActiveAssistantId(id)
            assistantRegistry.setActiveAssistant(id)
            settingsRepository.setPendingCloudAssistantSetup(false)
            withContext(Dispatchers.IO) {
                assistantRegistry.applyToEngine(engineHolder.engine())
            }
            _state.update {
                it.copy(pendingAssistantPromptId = null, pendingAssistantPromptName = "")
            }
        }
    }

    /**
     * User tapped "Not now" or dismissed the prompt — clear the fields
     * without changing the active assistant.
     */
    fun dismissPendingAssistantPrompt() {
        _state.update {
            it.copy(pendingAssistantPromptId = null, pendingAssistantPromptName = "")
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
        detailManifestJob?.cancel()
        _state.update { it.copy(detailManifest = null, detailManifestLoading = true) }
        detailManifestJob = viewModelScope.launch {
            // Pass the user's active locale through so the FFI returns
            // the localized variant (Italian description for Italian
            // users) — the canonical English manifest is the fallback
            // path inside the loader, not what we want to render here.
            val locale = settingsRepository.activeLocale.first()
            val result = runCatching {
                withContext(Dispatchers.IO) { skillRegistry.readInstalledManifest(id, locale) }
            }
            // Cancellation is a normal control-flow signal here (a newer
            // load superseded us) — let it bubble untouched. Touching
            // state from a cancelled job would just race with the
            // replacement load that's already running.
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
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
        detailManifestJob?.cancel()
        _state.update { it.copy(detailManifest = null, detailManifestLoading = true) }
        detailManifestJob = viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { skillRegistry.fetchManifestPreview(id) }
            }
            // Same cancellation handling as loadInstalledManifest — see
            // there for the reasoning.
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
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
        detailManifestJob?.cancel()
        detailManifestJob = null
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
            // Pass active locale through so labels render in the user's
            // language — `Salva i promemoria in` for Italian, not the
            // canonical English `Save reminders to`.
            val locale = settingsRepository.activeLocale.first()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    // First pass: read the schema (currentValue may be empty
                    // if the in-memory store hasn't been hydrated yet).
                    val schema = skillRegistry.getSkillSettings(skillId, locale)
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
                    skillRegistry.getSkillSettings(skillId, locale)
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
            val locale = settingsRepository.activeLocale.first()
            val refreshed =
                runCatching { skillRegistry.getSkillSettings(skillId, locale) }
                    .getOrDefault(emptyList())
            _state.update { it.copy(detailSettings = refreshed) }
        }
    }

    /**
     * Resolve a server-backed / interactive setting field by asking the
     * skill (via the engine) to compute the available options or validate
     * the current draft values. Used by the settings panel for fields
     * declaring `dependsOn` / `validate` — e.g. fetching the list of
     * remote models for a provider once an API key is entered.
     *
     * Bridges to [Dispatchers.IO] and returns the raw FFI result; the
     * caller (the composable) wraps this in `runCatching` and renders the
     * `error`/`message`/`options` it carries.
     */
    suspend fun querySkillSetting(
        skillId: String,
        field: String,
        values: Map<String, String>,
    ): FfiSettingsQueryResult = withContext(Dispatchers.IO) {
        engineHolder.engine().querySkillSetting(skillId, field, values)
    }

    suspend fun settingsAction(
        skillId: String,
        action: String,
        values: Map<String, String>,
    ): FfiSettingsQueryResult = withContext(Dispatchers.IO) {
        engineHolder.engine().settingsAction(skillId, action, values)
    }

    fun clearSkillSettings() {
        _state.update { it.copy(detailSettings = emptyList(), detailSettingsLoading = false) }
    }

    private fun friendlyError(t: Throwable): SkillErrorMessage {
        // Log the full exception (class + message + cause chain) before
        // mapping to the user-facing message. The friendly version
        // necessarily discards information; without this Log.w we'd lose the
        // actual failure mode (sha mismatch, signature error, network
        // timeout, etc.) and have nothing to debug from. Tagged AriSkill so
        // it shows up in `adb logcat -s AriSkill:V`.
        android.util.Log.w("AriSkill", "registry error in skill flow", t)
        // Map to a localizable string resource — resolved against the active
        // locale in the Composable layer (see skillErrorText), not baked to
        // English here.
        return when (t) {
            is FfiRegistryException.Network -> SkillErrorMessage(R.string.skills_error_network)
            is FfiRegistryException.BadStatus -> SkillErrorMessage(R.string.skills_error_bad_status)
            is FfiRegistryException.TooLarge -> SkillErrorMessage(R.string.skills_error_too_large)
            is FfiRegistryException.Integrity -> SkillErrorMessage(R.string.skills_error_integrity)
            // Generic registry-format problem (unparseable index, unsupported
            // version). No longer claims a connection fault — that's Network.
            is FfiRegistryException.Registry -> SkillErrorMessage(R.string.skills_error_registry)
            is FfiRegistryException.Store ->
                SkillErrorMessage(R.string.skills_error_store, t.message ?: "")
            is FfiRegistryException.NotFound -> SkillErrorMessage(R.string.skills_error_not_found)
            is FfiRegistryException.NotInstalled -> SkillErrorMessage(R.string.skills_error_not_installed)
            is FfiRegistryException.Manifest -> SkillErrorMessage(R.string.skills_error_manifest)
            is FfiRegistryException.ManifestUnavailable ->
                SkillErrorMessage(R.string.skills_error_manifest_unavailable)
            is FfiRegistryException.TrustKey -> SkillErrorMessage(R.string.skills_error_trust_key)
            else -> SkillErrorMessage(R.string.skills_error_generic)
        }
    }
}

/**
 * A localizable error to surface in the skills UI. Carries a string-resource
 * id (plus an optional arg for the one or two messages that embed detail)
 * rather than a pre-rendered string, so the Composable layer can resolve it
 * against the active locale. Adding a language later is then purely a matter
 * of adding `values-<locale>/strings.xml` entries — no code change here.
 */
data class SkillErrorMessage(
    @StringRes val resId: Int,
    val arg: String? = null,
)

data class SkillsScreenState(
    val installed: List<FfiInstalledSkill> = emptyList(),
    val updates: List<FfiSkillUpdate> = emptyList(),
    val browse: List<FfiBrowseEntry> = emptyList(),
    val checking: Boolean = false,
    val browsing: Boolean = false,
    val installingIds: Set<String> = emptySet(),
    val lastCheckOk: Boolean? = null,
    val lastBrowseOk: Boolean? = null,
    val errorMessage: SkillErrorMessage? = null,
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
    /**
     * The user's active Ari language, ISO 639-1 lowercase. Used by the
     * browse list to distinguish language-matched skills and by the
     * detail screen to gate installs of skills that don't support the
     * user's language behind a confirmation dialog.
     */
    val activeLocale: String = "en",
    /**
     * Set after a successful install of a `type: assistant` skill
     * when the user has no active assistant configured — drives the
     * "Set <name> as your default assistant?" AlertDialog. Cleared
     * by `confirmPendingAssistantPrompt` (which actually selects it)
     * or `dismissPendingAssistantPrompt` (which doesn't). Without
     * this nudge, users land back on the conversation screen with
     * `activeAssistantId == null` and get the no-skill-matched
     * fallback for any non-direct query.
     */
    val pendingAssistantPromptId: String? = null,
    val pendingAssistantPromptName: String = "",
)

private fun List<FfiInstalledSkill>.replaceOrAppend(skill: FfiInstalledSkill): List<FfiInstalledSkill> {
    val idx = indexOfFirst { it.id == skill.id }
    return if (idx >= 0) toMutableList().also { it[idx] = skill } else this + skill
}
