package dev.heyari.ari.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.wakeword.FalseWakeMonitor
import dev.heyari.ari.wakeword.FalseWakeRemedy
import dev.heyari.ari.wakeword.LadderReviewScheduler
import dev.heyari.ari.wakeword.TunedLadder
import dev.heyari.ari.wakeword.WakeWordRegistry
import dev.heyari.ari.wakeword.WakeWordSensitivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which question the screen is here to ask. */
enum class AdviceMode {
    /** Ari keeps waking for nothing, and offers one way to stop it. */
    BURST,

    /** Ari made itself stricter two days ago and wants to know if that broke anything. */
    REVIEW,
}

data class FalseWakeAdviceUiState(
    val mode: AdviceMode = AdviceMode.BURST,
    val falseWakes: Int = 0,
    val current: WakeWordSensitivity = WakeWordSensitivity.DEFAULT,
    val remedy: FalseWakeRemedy? = null,
    val tightenedAt: Long = 0L,
    /** Set once the user has chosen, so the screen can close itself. */
    val settled: Boolean = false,
)

/**
 * The screen a sensitivity notification opens.
 *
 * Both of its modes exist because of the same asymmetry. Ari can tell on its
 * own when it wakes too easily, so [AdviceMode.BURST] arrives unprompted. It
 * can never tell when it has stopped hearing somebody, so [AdviceMode.REVIEW]
 * has to come back and ask — and everything a BURST does is written down
 * precisely so that REVIEW can put it back.
 */
@HiltViewModel
class FalseWakeAdviceViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val falseWakeMonitor: FalseWakeMonitor,
    private val reviewScheduler: LadderReviewScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(FalseWakeAdviceUiState())
    val state: StateFlow<FalseWakeAdviceUiState> = _state.asStateFlow()

    fun load(mode: AdviceMode) {
        viewModelScope.launch {
            val level = WakeWordSensitivity.fromName(settingsRepository.wakeWordSensitivity.first())
            val model = WakeWordRegistry
                .byId(settingsRepository.activeWakeWordId.first())
                .withLadder(TunedLadder.parse(settingsRepository.wakeLadder.first()))
            _state.update {
                it.copy(
                    mode = mode,
                    falseWakes = falseWakeMonitor.recentCount(),
                    current = level,
                    remedy = FalseWakeRemedy.forLevel(level, model),
                    tightenedAt = settingsRepository.ladderTightenedAt.first(),
                )
            }
        }
    }

    /** Take the one suggestion on offer. */
    fun accept() {
        val current = _state.value
        viewModelScope.launch {
            when (val remedy = current.remedy) {
                is FalseWakeRemedy.StepDown ->
                    settingsRepository.setWakeWordSensitivity(remedy.to.name)

                is FalseWakeRemedy.Tighten -> {
                    // Remembered before it is overwritten, because REVIEW's
                    // only job is putting this back and it cannot recompute
                    // what was here — the old ladder may itself have been
                    // measured rather than built in.
                    settingsRepository.setLadderBeforeTightening(
                        settingsRepository.wakeLadder.first()
                    )
                    settingsRepository.setWakeLadder(remedy.ladder.format())
                    settingsRepository.setWakeWordSensitivity(WakeWordSensitivity.MEDIUM.name)
                    settingsRepository.setLadderTightenedAt(System.currentTimeMillis())
                    reviewScheduler.schedule()
                }

                FalseWakeRemedy.Exhausted, null -> Unit
            }
            _state.update { it.copy(settled = true) }
        }
    }

    /** Leave it alone. The count starts again from here. */
    fun ignore() = _state.update { it.copy(settled = true) }

    /** REVIEW: the tightening was fine. Stop asking. */
    fun keepTightened() {
        viewModelScope.launch {
            settingsRepository.setLadderTightenedAt(0L)
            settingsRepository.setLadderBeforeTightening(null)
            _state.update { it.copy(settled = true) }
        }
    }

    /**
     * REVIEW: somebody stopped being heard. Put the old ladder back.
     *
     * A null stored ladder is the correct thing to write when there was none
     * before — it returns the phone to the model's built-in numbers, which is
     * exactly where it was.
     */
    fun undoTightening() {
        viewModelScope.launch {
            settingsRepository.setWakeLadder(settingsRepository.ladderBeforeTightening.first())
            settingsRepository.setLadderBeforeTightening(null)
            settingsRepository.setLadderTightenedAt(0L)
            reviewScheduler.cancel()
            _state.update { it.copy(settled = true) }
        }
    }
}
