package dev.heyari.ari.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.wakeword.FalseWakeMonitor
import dev.heyari.ari.wakeword.FalseWakeRemedy
import dev.heyari.ari.wakeword.LadderReviewScheduler
import dev.heyari.ari.wakeword.TunedLadder
import dev.heyari.ari.wakeword.WakeWordRegistry
import dev.heyari.ari.wakeword.WakeWordSensitivity
import dev.heyari.ari.wakeword.WakeWordService
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
    /** The model the advice is about: the one that was running when the screen opened. */
    val modelId: String = WakeWordRegistry.default.id,
    val falseWakes: Int = 0,
    val current: WakeWordSensitivity = WakeWordSensitivity.DEFAULT,
    val remedy: FalseWakeRemedy? = null,
    val tightenedAt: Long = 0L,
    /** Set once the user has chosen, so the screen can close itself. */
    val settled: Boolean = false,
    /** The remedy that was just applied, so the screen can say so as it closes. */
    val applied: FalseWakeRemedy? = null,
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
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val falseWakeMonitor: FalseWakeMonitor,
    private val reviewScheduler: LadderReviewScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(FalseWakeAdviceUiState())
    val state: StateFlow<FalseWakeAdviceUiState> = _state.asStateFlow()

    fun load(mode: AdviceMode) {
        viewModelScope.launch {
            val active = WakeWordRegistry.byId(settingsRepository.activeWakeWordId.first())
            val level = WakeWordSensitivity.fromName(settingsRepository.wakeWordSensitivity(active.id).first())
            val model = active.withLadder(TunedLadder.parse(settingsRepository.wakeLadder(active.id).first()))
            _state.update {
                it.copy(
                    mode = mode,
                    modelId = active.id,
                    falseWakes = falseWakeMonitor.recentCount(),
                    current = level,
                    remedy = FalseWakeRemedy.forLevel(level, model),
                    tightenedAt = settingsRepository.ladderTightenedAt.first(),
                )
            }
        }
    }

    /**
     * Take the one suggestion on offer, for the model it was worked out for,
     * and restart listening so it applies from now rather than from whenever
     * the microphone next happens to close.
     */
    fun accept() {
        val current = _state.value
        viewModelScope.launch {
            val changed = when (val remedy = current.remedy) {
                is FalseWakeRemedy.StepDown -> {
                    settingsRepository.setWakeWordSensitivity(current.modelId, remedy.to.name)
                    true
                }

                is FalseWakeRemedy.Tighten -> {
                    val modelId = remedy.ladder.modelId
                    // Remembered before it is overwritten, because REVIEW's
                    // only job is putting this back and it cannot recompute
                    // what was here — the old ladder may itself have been
                    // measured rather than built in.
                    settingsRepository.setLadderBeforeTightening(
                        settingsRepository.wakeLadder(modelId).first()
                    )
                    settingsRepository.setLadderTightenedModel(modelId)
                    settingsRepository.setWakeLadder(modelId, remedy.ladder.format())
                    settingsRepository.setWakeWordSensitivity(modelId, WakeWordSensitivity.MEDIUM.name)
                    settingsRepository.setLadderTightenedAt(System.currentTimeMillis())
                    reviewScheduler.schedule()
                    true
                }

                FalseWakeRemedy.Exhausted, null -> false
            }
            if (changed) WakeWordService.restartIfRunning(context)
            _state.update { it.copy(settled = true, applied = current.remedy.takeIf { changed }) }
        }
    }

    /** Leave it alone. The count starts again from here. */
    fun ignore() = _state.update { it.copy(settled = true) }

    /** REVIEW: the tightening was fine. Stop asking. */
    fun keepTightened() {
        viewModelScope.launch {
            settingsRepository.setLadderTightenedAt(0L)
            settingsRepository.setLadderBeforeTightening(null)
            settingsRepository.setLadderTightenedModel(null)
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
            // A tightening from before ladders were per model never said which
            // model it was for; the active one is the only reasonable guess.
            val modelId = settingsRepository.ladderTightenedModel.first()
                ?: WakeWordRegistry.byId(settingsRepository.activeWakeWordId.first()).id
            settingsRepository.setWakeLadder(modelId, settingsRepository.ladderBeforeTightening.first())
            settingsRepository.setLadderBeforeTightening(null)
            settingsRepository.setLadderTightenedModel(null)
            settingsRepository.setLadderTightenedAt(0L)
            reviewScheduler.cancel()
            WakeWordService.restartIfRunning(context)
            _state.update { it.copy(settled = true) }
        }
    }
}
