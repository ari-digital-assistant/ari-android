package dev.heyari.ari.ui.conversation

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.models.ApplyEvent
import dev.heyari.ari.models.ModelUpdateApplier
import dev.heyari.ari.models.ModelUpdateChecker
import dev.heyari.ari.updates.PendingUpdateSummary
import dev.heyari.ari.updates.UpdatesPreferences
import dev.heyari.ari.updates.UpdatesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.heyari.ari.di.EngineHolder
import uniffi.ari_ffi.AssistantRegistry
import uniffi.ari_ffi.SkillRegistry
import java.io.File
import javax.inject.Inject

/**
 * Banner state for one of the two categories. Empty list = banner hidden;
 * non-empty = idle banner showing.
 */
data class UpdateBannerState(
    val modelUpdates: List<PendingUpdateSummary> = emptyList(),
    val skillUpdates: List<PendingUpdateSummary> = emptyList(),
    /** Non-null while an Update All is in flight; replaces the idle banner. */
    val applying: ApplyingProgress? = null,
    /** Set briefly after an Update All completes so the banner can show a result line before hiding. */
    val terminal: TerminalMessage? = null,
)

data class ApplyingProgress(
    val category: UpdatesPreferences.Category,
    val totalCount: Int,
    val currentIndex: Int,
    val currentDisplayName: String,
    /** -1 when byte-level progress isn't available (skill installs). */
    val bytesSoFar: Long,
    val totalBytes: Long,
)

data class TerminalMessage(
    val category: UpdatesPreferences.Category,
    val successCount: Int,
    val failCount: Int,
)

/**
 * Drives the in-app update banner on [ConversationScreen]. Reads pending
 * state from [UpdatesRepository], handles dismiss/details/Update All, and
 * runs the in-place sequential apply loop for both model and skill batches.
 *
 * Update All semantics: always sequential. Concurrent multi-GB downloads
 * on cellular would be hostile; failures don't abort the batch (one bad
 * manifest shouldn't block four good ones); a final terminal line shows
 * "X of Y installed".
 */
@HiltViewModel
class UpdateBannerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: UpdatesRepository,
    private val applier: ModelUpdateApplier,
    private val checker: ModelUpdateChecker,
    private val skillRegistry: SkillRegistry,
    private val assistantRegistry: AssistantRegistry,
    private val engineHolder: EngineHolder,
) : ViewModel() {
    private val _state = MutableStateFlow(UpdateBannerState())
    val state: StateFlow<UpdateBannerState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.modelUpdates.collect { updates ->
                _state.update { it.copy(modelUpdates = updates) }
            }
        }
        viewModelScope.launch {
            repository.skillUpdates.collect { updates ->
                _state.update { it.copy(skillUpdates = updates) }
            }
        }
        // Opportunistic refresh on launch. The periodic worker fires only
        // every 24h with a 6h flex window, so without this the banner
        // could lag a fresh update by up to a day — and on a fresh install
        // (or right after an app update) the worker may never have run
        // with the current code yet, so DataStore is empty. Doing one
        // quick fetch on init guarantees the banner reflects current
        // state every time the user opens the app. Failures are silent
        // (no network, registry down) — we just keep showing whatever
        // the worker last persisted.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val skills = skillRegistry.checkForUpdates()
                Log.i(TAG, "opportunistic skill check: ${skills.size} update(s) available")
                repository.recordCheck(
                    UpdatesPreferences.Category.SKILL,
                    UpdatesRepository.summariesFromSkillUpdates(skills),
                    postSystemNotification = false,
                )
            }.onFailure { Log.w(TAG, "opportunistic skill check failed: ${it.message}") }

            runCatching {
                val models = checker.checkForUpdates()
                Log.i(TAG, "opportunistic model check: ${models.size} update(s) available")
                repository.recordCheck(
                    UpdatesPreferences.Category.MODEL,
                    UpdatesRepository.summariesFromModelUpdates(models, context::getString),
                    postSystemNotification = false,
                )
            }.onFailure { Log.w(TAG, "opportunistic model check failed: ${it.message}") }
        }
    }

    fun dismissModelBanner() {
        viewModelScope.launch { repository.markSeen(UpdatesPreferences.Category.MODEL) }
    }

    fun dismissSkillBanner() {
        viewModelScope.launch { repository.markSeen(UpdatesPreferences.Category.SKILL) }
    }

    fun dismissTerminalMessage() {
        _state.update { it.copy(terminal = null) }
    }

    /**
     * Sequentially apply every currently-pending model update. Re-fetches
     * the live update list at start so we never apply stale-from-disk
     * data; if the worker found new updates between the user seeing the
     * banner and tapping the button, those land too.
     */
    fun applyAllModels() {
        if (_state.value.applying != null) return
        viewModelScope.launch {
            val updates = withContext(Dispatchers.IO) {
                runCatching { checker.checkForUpdates() }.getOrDefault(emptyList())
            }
            if (updates.isEmpty()) {
                repository.markSeen(UpdatesPreferences.Category.MODEL)
                return@launch
            }
            var success = 0
            var failed = 0
            updates.forEachIndexed { idx, update ->
                _state.update {
                    it.copy(
                        applying = ApplyingProgress(
                            category = UpdatesPreferences.Category.MODEL,
                            totalCount = updates.size,
                            currentIndex = idx + 1,
                            currentDisplayName = context.getString(update.target.displayNameRes),
                            bytesSoFar = 0L,
                            totalBytes = 0L,
                        ),
                    )
                }
                runCatching {
                    applier.apply(update).collect { event ->
                        when (event) {
                            is ApplyEvent.Progress -> {
                                _state.update {
                                    val current = it.applying ?: return@update it
                                    it.copy(
                                        applying = current.copy(
                                            bytesSoFar = event.bytesSoFar,
                                            totalBytes = event.totalBytes,
                                        ),
                                    )
                                }
                            }
                            is ApplyEvent.Completed -> success++
                            is ApplyEvent.Failed -> {
                                failed++
                                Log.w(TAG, "model update failed: ${event.reason}")
                            }
                            is ApplyEvent.Started -> { /* covered by ApplyingProgress above */ }
                        }
                    }
                }.onFailure {
                    failed++
                    Log.e(TAG, "model update threw", it)
                }
            }
            // Refresh the persisted pending list so any stragglers reappear
            // and successfully-installed entries drop out of the banner.
            val nowPending = withContext(Dispatchers.IO) {
                runCatching { checker.checkForUpdates() }.getOrDefault(emptyList())
            }
            repository.recordCheck(
                UpdatesPreferences.Category.MODEL,
                UpdatesRepository.summariesFromModelUpdates(nowPending, context::getString),
            )
            repository.markSeen(UpdatesPreferences.Category.MODEL)
            _state.update {
                it.copy(
                    applying = null,
                    terminal = TerminalMessage(
                        category = UpdatesPreferences.Category.MODEL,
                        successCount = success,
                        failCount = failed,
                    ),
                )
            }
        }
    }

    fun applyAllSkills() {
        if (_state.value.applying != null) return
        viewModelScope.launch {
            val updates = withContext(Dispatchers.IO) {
                runCatching { skillRegistry.checkForUpdates() }.getOrDefault(emptyList())
            }
            if (updates.isEmpty()) {
                repository.markSeen(UpdatesPreferences.Category.SKILL)
                return@launch
            }
            var success = 0
            var failed = 0
            updates.forEachIndexed { idx, update ->
                _state.update {
                    it.copy(
                        applying = ApplyingProgress(
                            category = UpdatesPreferences.Category.SKILL,
                            totalCount = updates.size,
                            currentIndex = idx + 1,
                            currentDisplayName = update.name.ifBlank { update.id },
                            // Skill FFI installs don't surface byte progress.
                            bytesSoFar = -1L,
                            totalBytes = -1L,
                        ),
                    )
                }
                runCatching {
                    withContext(Dispatchers.IO) { skillRegistry.installSkillUpdate(update.id) }
                }.fold(
                    onSuccess = { success++ },
                    onFailure = {
                        failed++
                        Log.w(TAG, "skill update failed for ${update.id}: ${it.message}")
                    },
                )
            }
            // Re-load engine community skills so freshly installed bundles
            // are picked up without a process restart, mirroring
            // SkillsViewModel.reloadEngineSkills.
            withContext(Dispatchers.IO) {
                runCatching {
                    val engine = engineHolder.engine()
                    val skillsDir = File(context.filesDir, "skills").absolutePath
                    val storageDir = File(context.filesDir, "skill-storage").absolutePath
                    engine.reloadCommunitySkills(skillsDir, storageDir)
                    assistantRegistry.reloadAndApply(engine)
                }.onFailure { Log.w(TAG, "engine reload after skill update failed", it) }
            }
            // Refresh persisted state so the banner's pending list reflects
            // what's still outstanding.
            val nowPending = withContext(Dispatchers.IO) {
                runCatching { skillRegistry.checkForUpdates() }.getOrDefault(emptyList())
            }
            repository.recordCheck(
                UpdatesPreferences.Category.SKILL,
                UpdatesRepository.summariesFromSkillUpdates(nowPending),
            )
            repository.markSeen(UpdatesPreferences.Category.SKILL)
            _state.update {
                it.copy(
                    applying = null,
                    terminal = TerminalMessage(
                        category = UpdatesPreferences.Category.SKILL,
                        successCount = success,
                        failCount = failed,
                    ),
                )
            }
        }
    }

    /**
     * Banner-side ack for "Details" tap: just mark seen so the banner
     * doesn't reappear when the user comes back. The actual navigation
     * is the screen's job.
     */
    fun acknowledgeModels() {
        viewModelScope.launch { repository.markSeen(UpdatesPreferences.Category.MODEL) }
    }

    fun acknowledgeSkills() {
        viewModelScope.launch { repository.markSeen(UpdatesPreferences.Category.SKILL) }
    }

    private companion object {
        const val TAG = "UpdateBannerViewModel"
    }
}
