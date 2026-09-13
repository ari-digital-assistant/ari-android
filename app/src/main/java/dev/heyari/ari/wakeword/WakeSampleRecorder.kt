package dev.heyari.ari.wakeword

import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import dev.heyari.ari.R
import dev.heyari.ari.audio.CaptureBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

/** Why a session could not start. Each one needs a different fix from the user. */
enum class RecorderProblem(@StringRes val messageRes: Int) {
    /** No audio arrived: the wake service is not holding the mic. */
    MIC_NOT_RUNNING(R.string.wake_samples_problem_mic),

    /** Something else already owns the capture bus — a voice turn, most likely. */
    BUS_BUSY(R.string.wake_samples_problem_busy),
}

/**
 * Records one segment of wake-phrase audio through the wake word service's own
 * microphone.
 *
 * It reads [CaptureBus] rather than opening its own `AudioRecord`, which is the
 * entire point: the evaluation set has to come down the same path production
 * audio does, and sharing the producer makes that true by construction instead
 * of by keeping two sets of capture flags in step. Arming the bus also
 * suppresses wake detection for free — see the `captureBus.armed` guard in
 * [WakeWordService] — so saying the phrase forty times does not launch forty
 * voice turns.
 *
 * A singleton so a session survives the settings page going away, and so a
 * second page cannot start a competing one.
 */
@Singleton
class WakeSampleRecorder @Inject constructor(
    private val captureBus: CaptureBus,
    private val store: WakeSampleStore,
) {
    sealed interface State {
        data object Idle : State

        /**
         * Armed and confirmed live, giving the speaker time to walk into
         * position. Skipped entirely when the countdown is switched off.
         */
        data class CountingDown(val secondsLeft: Int, val level: Float) : State

        data class Recording(
            val elapsedMs: Long,
            val level: Float,
            val utterances: Int,
            /** Takes the speaker has flagged with [mark]. */
            val marks: Int,
            /**
             * Share of the recording so far at or above [UTTERANCE_LEVEL].
             *
             * A recording of a room too quiet to register tells nobody
             * anything, and there is no way to tell from the level meter alone
             * — a near-empty bar looks the same as a bar that is working. Ten
             * minutes of a living room with the television on came back at 1%,
             * against 15-53% for every recording that turned out to be usable.
             */
            val loudPercent: Int,
        ) : State

        data class Problem(val problem: RecorderProblem) : State
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    /**
     * Whether this recorder is the current owner of the capture bus.
     *
     * [stop] must never disarm a bus it does not hold. A segment that ends on
     * its own — the [MAX_SEGMENT_MS] ceiling — releases the bus while the Stop
     * button is still on screen, so a late tap would otherwise land on whatever
     * armed it next, and the obvious next claimant is a voice turn's STT.
     * Guarded rather than merely checked, because the ceiling fires on the
     * drain coroutine and the tap arrives on the main thread.
     */
    private var owningBus = false

    /**
     * Elapsed-realtime the recording proper began, or 0 while counting down.
     * Read by [mark] on the main thread and written by the drain coroutine.
     */
    @Volatile
    private var recordingStartedAt = 0L

    private val marks = CopyOnWriteArrayList<Long>()

    fun start(segment: WakeSampleSegment, countdown: Boolean) {
        if (job != null) return
        job = scope.launch {
            try {
                record(segment, countdown)
            } finally {
                releaseBus()
                job = null
            }
        }
    }

    /**
     * Flag that a take was just spoken. Optional — a segment with no marks is
     * still usable, it just has to be split on energy alone.
     *
     * The mark lands where the speaker tapped, which is shortly AFTER the
     * phrase; the off-device split reads it as "a take ended near here" rather
     * than as a start offset.
     */
    fun mark() {
        val startedAt = recordingStartedAt
        if (startedAt == 0L) return
        marks += SystemClock.elapsedRealtime() - startedAt
    }

    /** Ends the segment and writes it. Closing the channel is what stops the drain. */
    fun stop() = releaseBus()

    @Synchronized
    private fun claimBus(): Channel<ShortArray>? {
        val channel = captureBus.arm(0f) ?: return null
        owningBus = true
        return channel
    }

    @Synchronized
    private fun releaseBus() {
        if (!owningBus) return
        owningBus = false
        captureBus.disarm()
    }

    /** Clears a [State.Problem] so the page can offer the start button again. */
    fun acknowledgeProblem() {
        if (_state.value is State.Problem) _state.value = State.Idle
    }

    private suspend fun record(segment: WakeSampleSegment, countdown: Boolean) {
        val channel = claimBus()
        if (channel == null) {
            _state.value = State.Problem(RecorderProblem.BUS_BUSY)
            return
        }
        // Before the countdown, not after: if the mic is not running there is
        // no point sending anybody to the far corner of the kitchen first.
        val first = withTimeoutOrNull(AUDIO_TIMEOUT_MS) { channel.receive() }
        if (first == null) {
            Log.w(TAG, "No audio within ${AUDIO_TIMEOUT_MS}ms — wake service is not capturing")
            _state.value = State.Problem(RecorderProblem.MIC_NOT_RUNNING)
            return
        }
        drain(channel, first, segment, countdown)
    }

    private suspend fun drain(
        channel: Channel<ShortArray>,
        first: ShortArray,
        segment: WakeSampleSegment,
        countdown: Boolean,
    ) {
        marks.clear()
        val countdownEndsAt =
            SystemClock.elapsedRealtime() + if (countdown) COUNTDOWN_MS else 0L
        val chunks = ArrayList<ShortArray>()
        var samples = 0
        var startedAt = 0L
        var utterances = 0
        var inUtterance = false
        var quietSince = 0L
        var loudChunks = 0
        var totalChunks = 0

        var chunk: ShortArray? = first
        while (chunk != null) {
            val now = SystemClock.elapsedRealtime()
            val level = levelOf(chunk)

            if (now < countdownEndsAt) {
                _state.value = State.CountingDown(
                    secondsLeft = ((countdownEndsAt - now) / 1000L).toInt() + 1,
                    level = level,
                )
                chunk = channel.receiveCatching().getOrNull()
                continue
            }
            if (startedAt == 0L) {
                startedAt = now
                recordingStartedAt = now
            }

            chunks += chunk
            samples += chunk.size

            totalChunks++
            if (level >= UTTERANCE_LEVEL) {
                loudChunks++
                quietSince = 0L
                if (!inUtterance) {
                    inUtterance = true
                    utterances++
                }
            } else if (inUtterance) {
                if (quietSince == 0L) {
                    quietSince = now
                } else if (now - quietSince >= UTTERANCE_GAP_MS) {
                    inUtterance = false
                    quietSince = 0L
                }
            }

            val elapsed = now - startedAt
            _state.value = State.Recording(
                elapsedMs = elapsed,
                level = level,
                utterances = utterances,
                marks = marks.size,
                loudPercent = loudChunks * 100 / totalChunks,
            )
            if (elapsed >= MAX_SEGMENT_MS) {
                Log.i(TAG, "Segment hit the ${MAX_SEGMENT_MS}ms ceiling — stopping")
                break
            }
            chunk = channel.receiveCatching().getOrNull()
        }

        recordingStartedAt = 0L
        _state.value = State.Idle
        if (samples == 0) return

        val pcm = ShortArray(samples)
        var offset = 0
        for (part in chunks) {
            part.copyInto(pcm, offset)
            offset += part.size
        }
        store.save(
            pcm,
            segment,
            System.currentTimeMillis(),
            samples * 1000L / SAMPLE_RATE,
            marks.toList(),
        )
    }

    /**
     * Chunk loudness as 0..1, mapped from RMS dBFS with [FLOOR_DB] as silence.
     * Drives the meter and the take counter, neither of which is data — the
     * recording is what gets kept, and takes are split off-device where the
     * thresholds can be chosen after hearing the room.
     */
    private fun levelOf(chunk: ShortArray): Float {
        if (chunk.isEmpty()) return 0f
        var sum = 0.0
        for (sample in chunk) {
            val value = sample.toDouble()
            sum += value * value
        }
        val rms = sqrt(sum / chunk.size)
        if (rms <= 0.0) return 0f
        val db = 20.0 * log10(rms / Short.MAX_VALUE.toDouble())
        return ((db - FLOOR_DB) / -FLOOR_DB).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val TAG = "WakeSampleRecorder"
        const val COUNTDOWN_MS = 10_000L
        const val AUDIO_TIMEOUT_MS = 1_000L
        const val MAX_SEGMENT_MS = 10 * 60 * 1000L
        const val SAMPLE_RATE = 16000L
        const val FLOOR_DB = -60.0
        const val UTTERANCE_LEVEL = 0.25f
        const val UTTERANCE_GAP_MS = 300L
    }
}
