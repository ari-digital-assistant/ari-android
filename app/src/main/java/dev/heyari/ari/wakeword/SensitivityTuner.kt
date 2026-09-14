package dev.heyari.ari.wakeword

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.audio.CaptureBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/** One person's score at every level, over the attempts they gave. */
data class TunedSpeaker(
    val name: String,
    val attempts: Int,
    val heardAt: Map<WakeWordSensitivity, Int>,
)

/**
 * Chooses a sensitivity by measuring it, rather than asking the user to guess
 * what "Medium" means for their voice in their kitchen.
 *
 * The audio comes off [CaptureBus] for the same reason [WakeSampleRecorder]
 * takes it from there: a setting tuned on a different microphone path is a
 * setting tuned for a phone nobody owns. Arming the bus also suppresses wake
 * detection, so saying the phrase five times does not start five voice turns.
 */
@Singleton
class SensitivityTuner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val captureBus: CaptureBus,
    private val samples: WakeSampleStore,
) {
    sealed interface State {
        data object Idle : State

        /**
         * Listening to the room before the first prompt. Not politeness: the
         * engine refuses to report anything for its first
         * MIN_SLICES_BEFORE_DETECTION frames, so an attempt with no run-in
         * behind it cannot be heard however clearly it was spoken.
         */
        data class WarmingUp(val secondsLeft: Int, val level: Float) : State

        /** Between prompts. The pause stops two takes running together. */
        data class GetReady(val attempt: Int, val total: Int) : State

        data class Listening(val attempt: Int, val total: Int, val level: Float) : State

        /** Running the attempts back through the engine at each level. */
        data object Scoring : State

        data class Problem(val problem: RecorderProblem) : State
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    private var owningBus = false

    /**
     * Record [ATTEMPTS] attempts from one person and score them.
     *
     * Returns null when the microphone never produced audio or somebody else
     * holds the bus — both of which the caller has to tell the user about,
     * because neither is fixable from this screen.
     */
    fun tune(name: String, model: WakeWordModel, onDone: (TunedSpeaker?) -> Unit) {
        if (job != null) return
        job = scope.launch {
            try {
                onDone(run(name, model))
            } finally {
                releaseBus()
                _state.value = State.Idle
                job = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
        releaseBus()
    }

    @Synchronized
    private fun claimBus(): Channel<ShortArray>? {
        // Two seconds of rewind, which is everything the ring buffer holds, so
        // the run-in starts before the user has finished tapping the button.
        val channel = captureBus.arm(2f) ?: return null
        owningBus = true
        return channel
    }

    @Synchronized
    private fun releaseBus() {
        if (!owningBus) return
        owningBus = false
        captureBus.disarm()
    }

    private suspend fun run(name: String, model: WakeWordModel): TunedSpeaker? {
        val channel = claimBus()
        if (channel == null) {
            _state.value = State.Problem(RecorderProblem.BUS_BUSY)
            return null
        }
        // Checked before the first prompt, not after: there is no point asking
        // somebody to say the phrase five times into a microphone nobody opened.
        if (withTimeoutOrNull(AUDIO_TIMEOUT_MS) { channel.receive() } == null) {
            Log.w(TAG, "No audio within ${AUDIO_TIMEOUT_MS}ms — wake service is not capturing")
            _state.value = State.Problem(RecorderProblem.MIC_NOT_RUNNING)
            return null
        }

        // One continuous recording for the whole session, exactly as the phone
        // hears it. Each attempt is then scored with the audio that preceded it
        // still in front of it, which is the only way the engine is past its
        // warm-up by the time the phrase arrives.
        val session = ArrayList<ShortArray>()
        val windows = ArrayList<IntRange>()

        collect(channel, session, WARMUP_MS) { elapsed, level ->
            State.WarmingUp(((WARMUP_MS - elapsed) / 1000L).toInt() + 1, level)
        }
        // Everything recorded before the first prompt: the room, with nobody
        // saying the phrase. This, and not the audio immediately before each
        // attempt, is what every attempt is warmed up on.
        val runIn = 0 until session.sumOf { it.size }
        for (index in 1..ATTEMPTS) {
            collect(channel, session, GET_READY_MS) { _, _ -> State.GetReady(index, ATTEMPTS) }
            val start = session.sumOf { it.size }
            collect(channel, session, LISTEN_MS) { _, level ->
                State.Listening(index, ATTEMPTS, level)
            }
            windows += start until session.sumOf { it.size }
        }
        releaseBus()

        _state.value = State.Scoring
        val pcm = flatten(session)

        // Kept, because what the tuner heard and what the same audio scores
        // offline are two different questions, and the only way to tell them
        // apart is to have the recording. Marks land at the end of each attempt
        // window, which is where the off-device split expects them.
        samples.save(
            pcm = pcm,
            segment = WakeSampleSegment(
                setName = name,
                phrase = "tuning",
                room = "tuning",
                distance = SampleDistance.NEAR,
                background = SampleBackground.QUIET,
            ),
            timestampMs = System.currentTimeMillis(),
            durationMs = pcm.size * 1000L / SAMPLE_RATE,
            marks = windows.map { it.last * 1000L / SAMPLE_RATE },
        )

        val buffer = loadModel(model.assetFilename) ?: return null
        return TunedSpeaker(
            name = name,
            attempts = windows.size,
            heardAt = WakeWordSensitivity.entries.associateWith { level ->
                windows.count { heard(pcm, runIn, it, buffer, model, level) }
            },
        )
    }

    /** Drains [channel] into [into] for [durationMs], reporting progress. */
    private suspend fun collect(
        channel: Channel<ShortArray>,
        into: MutableList<ShortArray>,
        durationMs: Long,
        state: (elapsed: Long, level: Float) -> State,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed >= durationMs) return
            val chunk = channel.receiveCatching().getOrNull() ?: return
            into += chunk
            _state.value = state(elapsed, levelOf(chunk))
        }
    }

    private fun flatten(chunks: List<ShortArray>): ShortArray {
        val pcm = ShortArray(chunks.sumOf { it.size })
        var offset = 0
        for (part in chunks) {
            part.copyInto(pcm, offset)
            offset += part.size
        }
        return pcm
    }

    /**
     * Whether [level] would have woken Ari on the attempt at [window].
     *
     * [runIn] is the room before the first prompt, replayed ahead of every
     * attempt. Two reasons, and both were found the hard way.
     *
     * The engine reports nothing for its first MIN_SLICES_BEFORE_DETECTION
     * probabilities — three seconds on a stride-3 model — so an attempt handed
     * to a fresh engine on its own can never fire however clearly it was
     * spoken: five attempts, zero heard, from a speaker who scores 16 of 16
     * offline.
     *
     * And the run-in has to be speech-free. Using the audio immediately before
     * each attempt puts the PREVIOUS attempt in it; the engine detects that,
     * detection resets the cool-off, and the next attempt lands inside a three
     * second blind period. It cost roughly one attempt per session and it cost
     * it only on models with a longer stride, which is to say it looked exactly
     * like the new model being worse than the one it replaced.
     *
     * A fresh engine per attempt, and that part is not defensive:
     * [MicroWakeWord.reset] clears the frontend and the detection window but
     * leaves the TFLite interpreter's variable tensors alone, so a reused engine
     * scores attempt five with attempt four still inside it.
     */
    private fun heard(
        pcm: ShortArray,
        runIn: IntRange,
        window: IntRange,
        modelBuffer: ByteBuffer,
        model: WakeWordModel,
        level: WakeWordSensitivity,
    ): Boolean {
        val point = model.operatingPoint(level)
        return MicroWakeWord(
            modelBuffer = modelBuffer,
            featureStepSizeMs = model.featureStepSizeMs,
            probabilityCutoff = point.probabilityCutoff,
            slidingWindowSize = point.slidingWindowSize,
        ).use { engine ->
            feed(engine, pcm, runIn)
            feed(engine, pcm, window)
        }
    }

    /** Returns whether the engine fired anywhere in [range]. */
    private fun feed(engine: MicroWakeWord, pcm: ShortArray, range: IntRange): Boolean {
        var detected = false
        var offset = range.first
        while (offset <= range.last) {
            val end = minOf(offset + CHUNK_SIZE, range.last + 1)
            if (engine.processAudio(pcm.copyOfRange(offset, end))) detected = true
            offset = end
        }
        return detected
    }

    private fun loadModel(filename: String): ByteBuffer? = try {
        val bytes = context.assets.open(filename).use { it.readBytes() }
        ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            rewind()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load $filename for tuning", e)
        null
    }

    private fun levelOf(chunk: ShortArray): Float {
        if (chunk.isEmpty()) return 0f
        var sum = 0.0
        for (sample in chunk) {
            val value = sample.toDouble()
            sum += value * value
        }
        val rms = Math.sqrt(sum / chunk.size)
        if (rms <= 0.0) return 0f
        val db = 20.0 * Math.log10(rms / Short.MAX_VALUE.toDouble())
        return ((db - FLOOR_DB) / -FLOOR_DB).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG = "SensitivityTuner"
        const val ATTEMPTS = 5

        /** Attempts a person must be heard on before a level is good enough. */
        const val REQUIRED_HITS = 4

        private const val GET_READY_MS = 700L

        /**
         * Run-in fed to the engine before each attempt. Comfortably past
         * MIN_SLICES_BEFORE_DETECTION (100 probabilities — 3 s on a stride-3
         * model, more on anything slower), because the app cannot see a model's
         * stride from this side of the JNI boundary.
         */
        private const val WARMUP_MS = 5_000L
        private const val WARMUP_SAMPLES = (WARMUP_MS * 16).toInt()
        private const val LISTEN_MS = 2_500L
        private const val AUDIO_TIMEOUT_MS = 1_000L
        private const val CHUNK_SIZE = 480
        private const val SAMPLE_RATE = 16000L
        private const val FLOOR_DB = -60.0

        /**
         * The strictest level that heard every person at least [REQUIRED_HITS]
         * times, or null when none did. Strictness is [WakeWordSensitivity]'s
         * own order — the numbers behind it differ per model, so they cannot be
         * compared here.
         *
         * Across everybody, not just whoever is holding the phone. The whole
         * reason this screen exists is that the owner's voice is the one voice
         * that never needed tuning: measure only him and the honest answer is
         * LOW, complete with a green tick, while everyone else in the house
         * goes on being ignored.
         */
        fun pickLevel(speakers: List<TunedSpeaker>): WakeWordSensitivity? {
            if (speakers.isEmpty()) return null
            return WakeWordSensitivity.loosestFirst
                .reversed()
                .firstOrNull { level ->
                    speakers.all { (it.heardAt[level] ?: 0) >= REQUIRED_HITS }
                }
        }
    }
}
