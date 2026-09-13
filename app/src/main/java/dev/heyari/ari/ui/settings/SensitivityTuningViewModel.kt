package dev.heyari.ari.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.wakeword.RecorderProblem
import dev.heyari.ari.wakeword.SensitivityTuner
import dev.heyari.ari.wakeword.TunedSpeaker
import dev.heyari.ari.wakeword.WakeWordRegistry
import dev.heyari.ari.wakeword.WakeWordSensitivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SensitivityTuningUiState(
    val name: String = "",
    val speakers: List<TunedSpeaker> = emptyList(),
    val tuner: SensitivityTuner.State = SensitivityTuner.State.Idle,
    /** Set once at least one person has been measured. */
    val chosen: WakeWordSensitivity? = null,
    /**
     * True when no level heard everybody often enough. The chosen level is then
     * HIGH by fallback, and saying so is the point — a green tick over a setting
     * that does not work is worse than no screen at all.
     */
    val shortOfTheBar: Boolean = false,
    val problem: RecorderProblem? = null,
) {
    /**
     * Whoever the chosen level is being held open for: the enrolled person
     * heard least often at it. Naming them is the difference between "Ari is
     * set to High" reading as a per-person result and reading as one setting
     * the household shares.
     */
    val constrainedBy: TunedSpeaker?
        get() = if (speakers.size < 2) null
        else speakers.minByOrNull { it.heardAt[chosen] ?: 0 }

    val canStart: Boolean
        get() = name.isNotBlank() && tuner is SensitivityTuner.State.Idle

    val isBusy: Boolean get() = tuner !is SensitivityTuner.State.Idle
}

@HiltViewModel
class SensitivityTuningViewModel @Inject constructor(
    private val tuner: SensitivityTuner,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SensitivityTuningUiState())
    val state: StateFlow<SensitivityTuningUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            tuner.state.collect { next -> _state.update { it.copy(tuner = next) } }
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun start() {
        val current = _state.value
        if (!current.canStart) return
        _state.update { it.copy(problem = null) }
        viewModelScope.launch {
            val model = WakeWordRegistry.byId(settingsRepository.activeWakeWordId.first())
            tuner.tune(current.name.trim(), model) { result ->
                if (result == null) {
                    _state.update {
                        it.copy(problem = (tuner.state.value as? SensitivityTuner.State.Problem)?.problem)
                    }
                    return@tune
                }
                _state.update { previous ->
                    val speakers = previous.speakers.filterNot { it.name == result.name } + result
                    val picked = SensitivityTuner.pickLevel(speakers)
                    previous.copy(
                        name = "",
                        speakers = speakers,
                        chosen = picked ?: WakeWordSensitivity.HIGH,
                        shortOfTheBar = picked == null,
                    )
                }
                apply()
            }
        }
    }

    /** Writes the measured level through, so the screen has actually done something. */
    private fun apply() {
        val level = _state.value.chosen ?: return
        viewModelScope.launch { settingsRepository.setWakeWordSensitivity(level.name) }
    }

    fun dismissProblem() = _state.update { it.copy(problem = null) }

    override fun onCleared() {
        tuner.cancel()
    }
}
