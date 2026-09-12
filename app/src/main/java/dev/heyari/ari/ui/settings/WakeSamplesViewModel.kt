package dev.heyari.ari.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.heyari.ari.wakeword.SampleBackground
import dev.heyari.ari.wakeword.SampleDistance
import dev.heyari.ari.wakeword.WakeSampleRecorder
import dev.heyari.ari.wakeword.WakeSampleSegment
import dev.heyari.ari.wakeword.WakeSampleStore
import dev.heyari.ari.wakeword.WakeSampleSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WakeSamplesUiState(
    val setName: String = "",
    val phrase: String = "",
    /** True once the set is named and the phrase fixed: both are then read-only. */
    val setStarted: Boolean = false,
    val room: String = "",
    val distance: SampleDistance? = null,
    val background: SampleBackground? = null,
    /** Rooms already used in this set, offered as one-tap chips. */
    val knownRooms: List<String> = emptyList(),
    /**
     * Whether to wait before recording starts. Off by default: most positions
     * are within reach of the phone, and the wait is dead air in the file.
     */
    val countdown: Boolean = false,
    val segmentsThisSet: Int = 0,
    val recorder: WakeSampleRecorder.State = WakeSampleRecorder.State.Idle,
    /** Everything recorded so far, newest first. */
    val segments: List<WakeSampleSummary> = emptyList(),
) {
    val canStartSet: Boolean get() = setName.isNotBlank() && phrase.isNotBlank()

    /**
     * Every condition is required. Grouping recordings by room and distance is
     * most of what makes the evaluation set worth having — without the labels
     * it is an hour of audio nobody can say anything about.
     */
    val canRecord: Boolean
        get() = setStarted && room.isNotBlank() && distance != null && background != null &&
            recorder is WakeSampleRecorder.State.Idle

    val isBusy: Boolean
        get() = recorder is WakeSampleRecorder.State.CountingDown ||
            recorder is WakeSampleRecorder.State.Recording
}

@HiltViewModel
class WakeSamplesViewModel @Inject constructor(
    private val recorder: WakeSampleRecorder,
    private val store: WakeSampleStore,
) : ViewModel() {

    private val _state = MutableStateFlow(WakeSamplesUiState())
    val state: StateFlow<WakeSamplesUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(segments = store.segments()) }
        viewModelScope.launch {
            recorder.state.collect { recorderState ->
                val wasBusy = _state.value.isBusy
                _state.update { it.copy(recorder = recorderState) }
                // A segment just landed: refresh the counters off the store
                // rather than assuming the write happened.
                if (wasBusy && recorderState is WakeSampleRecorder.State.Idle) {
                    _state.update {
                        it.copy(
                            segments = store.segments(),
                            segmentsThisSet = it.segmentsThisSet + 1,
                            knownRooms = (it.knownRooms + it.room).distinct(),
                        )
                    }
                }
            }
        }
    }

    fun setName(value: String) = _state.update { it.copy(setName = value) }

    fun setPhrase(value: String) = _state.update { it.copy(phrase = value) }

    fun startSet() = _state.update { it.copy(setStarted = true) }

    fun endSet() = _state.update {
        // The countdown is a preference about how this person records, not part
        // of the set, so it survives into the next one.
        WakeSamplesUiState(segments = store.segments(), countdown = it.countdown)
    }

    fun setRoom(value: String) = _state.update { it.copy(room = value) }

    fun setDistance(value: SampleDistance) = _state.update { it.copy(distance = value) }

    fun setBackground(value: SampleBackground) = _state.update { it.copy(background = value) }

    fun setCountdown(value: Boolean) = _state.update { it.copy(countdown = value) }

    fun record() {
        val current = _state.value
        if (!current.canRecord) return
        recorder.start(
            WakeSampleSegment(
                setName = current.setName.trim(),
                phrase = current.phrase.trim(),
                room = current.room.trim(),
                distance = current.distance!!,
                background = current.background!!,
            ),
            countdown = current.countdown,
        )
    }

    fun mark() = recorder.mark()

    fun stop() = recorder.stop()

    fun acknowledgeProblem() = recorder.acknowledgeProblem()

    fun clear() {
        store.clear()
        _state.update { it.copy(segments = store.segments(), segmentsThisSet = 0) }
    }

    fun shareIntent(): Intent? = store.shareIntent()
}
