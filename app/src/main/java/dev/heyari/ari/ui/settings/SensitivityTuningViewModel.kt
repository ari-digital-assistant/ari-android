package dev.heyari.ari.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.wakeword.LadderFit
import dev.heyari.ari.wakeword.RecorderProblem
import dev.heyari.ari.wakeword.SensitivityTuner
import dev.heyari.ari.wakeword.Take
import dev.heyari.ari.wakeword.TunedSession
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
    val sessions: List<TunedSession> = emptyList(),
    val tuner: SensitivityTuner.State = SensitivityTuner.State.Idle,
    /** The ladder the sessions so far imply. Null until one has been recorded. */
    val fit: LadderFit? = null,
    val tuneDuringNormalUse: Boolean = false,
    val problem: RecorderProblem? = null,
) {
    val canStart: Boolean get() = tuner is SensitivityTuner.State.Idle

    val isBusy: Boolean get() = tuner !is SensitivityTuner.State.Idle

    /** Session numbers, not names: the tuner needs them kept apart, not labelled. */
    val nextSession: Int get() = sessions.size + 1
}

/**
 * Drives the tuning screen: record a session, solve a ladder, write it through.
 *
 * Sessions are numbered rather than named. Keeping them separate is what the
 * measurement needs — the household's worst decoy is the one that sets the
 * ladder, and averaging everybody together would hide it — but who each one
 * belongs to is the user's business, and asking is friction on a screen that
 * already asks for twelve spoken takes.
 */
@HiltViewModel
class SensitivityTuningViewModel @Inject constructor(
    private val tuner: SensitivityTuner,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SensitivityTuningUiState())
    val state: StateFlow<SensitivityTuningUiState> = _state.asStateFlow()

    /** The prompts for the session in progress, in order. */
    var takes: List<Take> = emptyList()
        private set

    init {
        viewModelScope.launch {
            tuner.state.collect { next -> _state.update { it.copy(tuner = next) } }
        }
        viewModelScope.launch {
            settingsRepository.tuneDuringNormalUse.collect { on ->
                _state.update { it.copy(tuneDuringNormalUse = on) }
            }
        }
    }

    fun start() {
        val current = _state.value
        if (!current.canStart) return
        _state.update { it.copy(problem = null) }
        takes = SensitivityTuner.takeList()
        viewModelScope.launch {
            val model = WakeWordRegistry.byId(settingsRepository.activeWakeWordId.first())
            tuner.tune(current.nextSession, takes, model) { session ->
                if (session == null) {
                    _state.update {
                        it.copy(problem = (tuner.state.value as? SensitivityTuner.State.Problem)?.problem)
                    }
                    return@tune
                }
                val sessions = _state.value.sessions + session
                val fit = SensitivityTuner.fit(sessions, model)
                _state.update { it.copy(sessions = sessions, fit = fit) }
                apply(fit)
            }
        }
    }

    fun setTuneDuringNormalUse(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setTuneDuringNormalUse(enabled) }
    }

    fun dismissProblem() = _state.update { it.copy(problem = null) }

    /**
     * Writes the solved ladder through, and puts the user on its middle rung.
     *
     * MEDIUM every time, including when the previous setting was stricter. The
     * ladder has just been rebuilt around this household, so the old rung name
     * refers to a number that no longer exists — carrying it over would apply a
     * position on the old curve to the new one, which is the error this whole
     * area of the app has now made twice.
     *
     * An overlapping fit is still written. It is the strictest ladder that the
     * session's own decoys justify, which is worth having; what it is not is a
     * ladder that will reliably hear this person, and the screen says so rather
     * than the code quietly declining to save.
     */
    private fun apply(fit: LadderFit?) {
        val ladder = fit?.ladder ?: return
        viewModelScope.launch {
            settingsRepository.setWakeLadder(ladder.format())
            settingsRepository.setWakeWordSensitivity(WakeWordSensitivity.MEDIUM.name)
            // A hand-tuned ladder settles the question the scheduled review
            // exists to ask, so retire it rather than letting it arrive in two
            // days asking about a tightening the user has since overwritten.
            settingsRepository.setLadderTightenedAt(0L)
            settingsRepository.setLadderBeforeTightening(null)
        }
    }

    override fun onCleared() {
        tuner.cancel()
    }
}
