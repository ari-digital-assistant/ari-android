package dev.heyari.ari.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.heyari.ari.audio.ClipStats
import dev.heyari.ari.contrib.ContributionUploader
import dev.heyari.ari.contrib.DeleteOutcome
import dev.heyari.ari.contrib.RecordingChoices
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.stt.UtteranceCaptureStore
import dev.heyari.ari.wakeword.WakeCaptureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecordingsState(
    /** Which categories are being written to disk. */
    val keep: RecordingChoices = RecordingChoices(),
    /** Which of those the user has agreed to contribute. */
    val share: RecordingChoices = RecordingChoices(),
    val wakeCaptureStats: ClipStats = ClipStats(0, 0L),
    val utteranceCaptureStats: ClipStats = ClipStats(0, 0L),
    /** Null until the user first turns sharing on. */
    val contributorId: String? = null,
    /** True once something has actually been uploaded — see the repository. */
    val hasSharedRecordings: Boolean = false,
    val deleting: Boolean = false,
    /** Set when a deletion finishes, cleared once the screen has shown it. */
    val deleteOutcome: DeleteOutcome? = null,
)

/**
 * Backs the Recordings section of Settings › Developer and the matching step
 * of the first-run wizard.
 *
 * Split out of [SettingsViewModel] rather than added to it: these are the
 * privacy-sensitive switches, the only ones that can send audio off the
 * device, and they are easier to reason about in one small file than buried in
 * the thousand-line one.
 */
@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val wakeCaptureStore: WakeCaptureStore,
    private val utteranceCaptureStore: UtteranceCaptureStore,
    private val uploader: ContributionUploader,
) : ViewModel() {

    private val _state = MutableStateFlow(RecordingsState())
    val state: StateFlow<RecordingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            combine(
                settingsRepository.keepWakeAudio,
                settingsRepository.keepFalseTriggerAudio,
                settingsRepository.keepUtteranceAudio,
                settingsRepository.keepEverythingAudio,
                ::RecordingChoices,
            ).collect { keep ->
                // Stats are re-read alongside the toggles because turning one
                // on is the moment a user looks at how much is stored.
                val wake = wakeCaptureStore.stats()
                val utterance = utteranceCaptureStore.stats()
                _state.update {
                    it.copy(
                        keep = keep,
                        wakeCaptureStats = wake,
                        utteranceCaptureStats = utterance,
                    )
                }
            }
        }

        viewModelScope.launch {
            combine(
                settingsRepository.shareWakeAudio,
                settingsRepository.shareFalseTriggerAudio,
                settingsRepository.shareUtteranceAudio,
                settingsRepository.shareEverythingAudio,
                ::RecordingChoices,
            ).collect { share -> _state.update { it.copy(share = share) } }
        }

        viewModelScope.launch {
            settingsRepository.contributorIdIfMinted.collect { id ->
                _state.update { it.copy(contributorId = id) }
            }
        }

        viewModelScope.launch {
            settingsRepository.hasSharedRecordings.collect { shared ->
                _state.update { it.copy(hasSharedRecordings = shared) }
            }
        }
    }

    fun setKeepWake(enabled: Boolean) = updateKeep(enabled) {
        settingsRepository.setKeepWakeAudio(enabled)
        if (!enabled) settingsRepository.setShareWakeAudio(false)
    }

    fun setKeepFalseTrigger(enabled: Boolean) = updateKeep(enabled) {
        settingsRepository.setKeepFalseTriggerAudio(enabled)
        if (!enabled) settingsRepository.setShareFalseTriggerAudio(false)
    }

    fun setKeepCommand(enabled: Boolean) = updateKeep(enabled) {
        settingsRepository.setKeepUtteranceAudio(enabled)
        if (!enabled) settingsRepository.setShareUtteranceAudio(false)
    }

    fun setKeepEverything(enabled: Boolean) = updateKeep(enabled) {
        settingsRepository.setKeepEverythingAudio(enabled)
        if (!enabled) settingsRepository.setShareEverythingAudio(false)
    }

    fun setShareWake(enabled: Boolean) = updateShare(enabled, RecordingChoices(wake = true)) {
        settingsRepository.setShareWakeAudio(enabled)
    }

    fun setShareFalseTrigger(enabled: Boolean) =
        updateShare(enabled, RecordingChoices(falseTrigger = true)) {
            settingsRepository.setShareFalseTriggerAudio(enabled)
        }

    fun setShareCommand(enabled: Boolean) = updateShare(enabled, RecordingChoices(command = true)) {
        settingsRepository.setShareUtteranceAudio(enabled)
    }

    fun setShareEverything(enabled: Boolean) =
        updateShare(enabled, RecordingChoices(everything = true)) {
            settingsRepository.setShareEverythingAudio(enabled)
        }

    fun clearWakeCaptures() {
        viewModelScope.launch(Dispatchers.IO) {
            wakeCaptureStore.clear()
            val stats = wakeCaptureStore.stats()
            _state.update { it.copy(wakeCaptureStats = stats) }
        }
    }

    fun clearUtteranceCaptures() {
        viewModelScope.launch(Dispatchers.IO) {
            utteranceCaptureStore.clear()
            val stats = utteranceCaptureStore.stats()
            _state.update { it.copy(utteranceCaptureStats = stats) }
        }
    }

    fun wakeCaptureShareIntent(): Intent? = wakeCaptureStore.shareIntent()

    fun utteranceCaptureShareIntent(): Intent? = utteranceCaptureStore.shareIntent()

    fun deleteSharedData() {
        viewModelScope.launch {
            _state.update { it.copy(deleting = true) }
            val outcome = uploader.deleteEverythingShared()
            _state.update { it.copy(deleting = false, deleteOutcome = outcome) }
        }
    }

    /** Called once the screen has reported the outcome, so it isn't shown twice. */
    fun consumeDeleteOutcome() {
        _state.update { it.copy(deleteOutcome = null) }
    }

    /**
     * Take on a contributor code from a previous install.
     *
     * [onResult] carries whether the code was one, so the dialog can keep the
     * typo on screen rather than closing over it. Adopting marks the
     * contributor as having shared: the entire reason to type an old code in
     * is that there is something out there under it to reach.
     */
    fun adoptContributorCode(code: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val adopted = settingsRepository.adoptContributorId(code)
            if (adopted) settingsRepository.setHasSharedRecordings(true)
            onResult(adopted)
        }
    }

    private fun updateKeep(enabled: Boolean, write: suspend () -> Unit) {
        viewModelScope.launch {
            write()
            // Withdrawing a category can leave nothing shared at all, which is
            // the moment the periodic sweep should stop being scheduled.
            if (!enabled) uploader.syncSchedule()
        }
    }

    private fun updateShare(
        enabled: Boolean,
        covers: RecordingChoices,
        write: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            // Sealed BEFORE the switch is written, never after: a sweep that
            // raced the write would otherwise find a null watermark and send
            // the whole directory, which is the one thing this must not do.
            if (enabled) uploader.sealExistingClips(covers)
            write()
            // Mint the id on the way in, not at the first upload: the code is
            // what the user needs in order to ask for deletion later, so it
            // has to be on screen from the moment they agree to share.
            if (enabled) settingsRepository.contributorId()
            uploader.syncSchedule()
        }
    }
}
