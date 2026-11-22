package dev.heyari.ari.wakeword

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.R
import dev.heyari.ari.audio.ListenFeed
import dev.heyari.ari.audio.pcmBytes
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
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.FfiOperatingPoint
import uniffi.ari_ffi.FfiTunedSession
import uniffi.ari_ffi.FfiVoiceException
import uniffi.ari_ffi.FfiWakeDetector
import uniffi.ari_ffi.FfiWakeModelFiles
import uniffi.ari_ffi.FfiWakeWordModel
import uniffi.ari_ffi.tunedLadderCutoffRange
import uniffi.ari_ffi.wakeModelFiles

/** What a take asked the person to say. */
enum class TakeKind { PHRASE, DECOY }

/** One prompt in a session: what to say, and whether it should wake Ari. */
data class Take(val kind: TakeKind, @StringRes val promptRes: Int)

/**
 * Chooses a sensitivity by measuring it, rather than asking the user to guess
 * what "Medium" means for their voice in their kitchen.
 *
 * The audio comes off [ListenFeed] for the same reason [WakeSampleRecorder]
 * takes it from there: a setting tuned on a different microphone path is a
 * setting tuned for a phone nobody owns. Arming the bus also suppresses wake
 * detection, so saying the phrase five times does not start five voice turns.
 */
@Singleton
class SensitivityTuner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val listenFeed: ListenFeed,
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
        data class GetReady(val attempt: Int, val total: Int, val prompt: Take) : State

        data class Listening(
            val attempt: Int,
            val total: Int,
            val level: Float,
            val prompt: Take,
        ) : State

        /**
         * Searching each take for the cutoff it stops firing at. Carries
         * progress because it is no longer instant: finding a threshold costs
         * several passes per take, and a spinner with no end in sight reads as
         * a hang.
         */
        data class Scoring(val done: Int, val total: Int) : State

        data class Problem(val problem: RecorderProblem) : State
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    private var owningBus = false

    /**
     * Record [TAKES] takes for session [id] and score them.
     *
     * Returns null when the microphone never produced audio or somebody else
     * holds the bus — both of which the caller has to tell the user about,
     * because neither is fixable from this screen.
     */
    fun tune(id: Int, takes: List<Take>, model: FfiWakeWordModel, onDone: (FfiTunedSession?) -> Unit) {
        if (job != null) return
        job = scope.launch {
            try {
                onDone(run(id, takes, model))
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
        val channel = listenFeed.arm(2f) ?: return null
        owningBus = true
        return channel
    }

    @Synchronized
    private fun releaseBus() {
        if (!owningBus) return
        owningBus = false
        listenFeed.disarm()
    }

    private suspend fun run(id: Int, takes: List<Take>, model: FfiWakeWordModel): FfiTunedSession? {
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
        TuningCues().use { cues ->
            takes.forEachIndexed { index, take ->
                val attempt = index + 1
                cues.getReady()
                collect(channel, session, GET_READY_MS) { _, _ ->
                    State.GetReady(attempt, takes.size, take)
                }
                // The screen says "now" with the beep, but the attempt starts
                // once the beep is out of the room: the microphone hears the
                // speaker, and an attempt is meant to hold the person alone.
                cues.sayIt()
                collect(channel, session, CUE_CLEAR_MS) { _, level ->
                    State.Listening(attempt, takes.size, level, take)
                }
                val start = session.sumOf { it.size }
                collect(channel, session, LISTEN_MS) { _, level ->
                    State.Listening(attempt, takes.size, level, take)
                }
                windows += start until session.sumOf { it.size }
            }
        }
        releaseBus()

        _state.value = State.Scoring(0, takes.size)
        val pcm = flatten(session)

        // Kept, because what the tuner heard and what the same audio scores
        // offline are two different questions, and the only way to tell them
        // apart is to have the recording. Marks land at the end of each attempt
        // window, which is where the off-device split expects them.
        // Only the phrase takes are marked. A mark means "the wake phrase was
        // said here", and the off-device harness scores recall against exactly
        // that; marking the decoys too would file half this recording as takes
        // the model failed to hear, which is the opposite of what they are.
        samples.save(
            pcm = pcm,
            segment = WakeSampleSegment(
                setName = "session-$id",
                phrase = "tuning",
                room = "tuning",
                distance = SampleDistance.NEAR,
                background = SampleBackground.QUIET,
            ),
            timestampMs = System.currentTimeMillis(),
            durationMs = pcm.size * 1000L / SAMPLE_RATE,
            marks = windows.filterIndexed { index, _ -> takes[index].kind == TakeKind.PHRASE }
                .map { it.last * 1000L / SAMPLE_RATE },
        )

        val files = modelFiles(model) ?: return null
        val phrases = mutableListOf<Float>()
        val decoys = mutableListOf<Float>()
        windows.forEachIndexed { index, window ->
            _state.value = State.Scoring(index, takes.size)
            val score = threshold(pcm, runIn, window, files, model)
            if (takes[index].kind == TakeKind.PHRASE) phrases += score else decoys += score
        }
        return FfiTunedSession(id = id, phraseScores = phrases, decoyScores = decoys)
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
     * microWakeWord's reset() clears the frontend and the detection window but
     * leaves the TFLite interpreter's variable tensors alone, so a reused engine
     * scores attempt five with attempt four still inside it.
     */
    private fun fires(
        pcm: ShortArray,
        runIn: IntRange,
        window: IntRange,
        files: FfiWakeModelFiles,
        model: FfiWakeWordModel,
        cutoff: Float,
    ): Boolean = FfiWakeDetector(
        model = model,
        point = FfiOperatingPoint(cutoff, model.medium.slidingWindowSize),
        resetOnDetection = true,
        files = files,
    ).use { engine ->
        feed(engine, pcm, runIn)
        feed(engine, pcm, window)
    }

    /**
     * The highest cutoff at which this take still wakes Ari, or 0 if none does.
     *
     * Found by bisection rather than by sweeping a grid, which halves the work
     * and answers more precisely. It is sound because detection is monotonic in
     * the cutoff: the engine fires when the window's summed probability exceeds
     * `cutoff * windowSize`, so a take that fires at some cutoff fires at every
     * lower one. If that rule ever changes in `MicroWakeWordEngine.cpp` this
     * search silently starts returning nonsense, which is why the port of the
     * rule is tested rather than assumed.
     *
     * The top of [tunedLadderCutoffRange] bounds the search because past it
     * the int8 quantisation makes neighbouring cutoffs identical and the
     * search would be bisecting noise.
     */
    private fun threshold(
        pcm: ShortArray,
        runIn: IntRange,
        window: IntRange,
        files: FfiWakeModelFiles,
        model: FfiWakeWordModel,
    ): Float {
        fun firesAt(cutoff: Float) = fires(pcm, runIn, window, files, model, cutoff)
        val range = tunedLadderCutoffRange()
        if (!firesAt(range.min)) return 0f
        if (firesAt(range.max)) return range.max
        var low = range.min
        var high = range.max
        repeat(SEARCH_STEPS) {
            val mid = (low + high) / 2f
            if (firesAt(mid)) low = mid else high = mid
        }
        return low
    }

    /** Returns whether the engine fired anywhere in [range], fed in the capture loop's reads. */
    private fun feed(engine: FfiWakeDetector, pcm: ShortArray, range: IntRange): Boolean =
        engine.processReads(pcmBytes(pcm, range.first, range.last + 1), CHUNK_SIZE.toUInt())

    /** The model's files, read once per session and shared by every engine the search builds. */
    private fun modelFiles(model: FfiWakeWordModel): FfiWakeModelFiles? = try {
        val bytes = wakeModelFiles(model).map { name -> context.assets.open(name).use { it.readBytes() } }
        FfiWakeModelFiles(bytes[0], bytes.getOrNull(1), bytes.getOrNull(2))
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load ${model.assetFilename} for tuning", e)
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

        /**
         * Takes in a session, split evenly between the phrase and the decoys.
         *
         * Twelve, because the decoys are what set the ladder and six of them is
         * the fewest that can catch a habitual near-miss. There is no gain in
         * running longer: the session is not searching for a threshold by trial
         * and error, it is measuring where each take sits, and the twelfth take
         * measures exactly as well as the fiftieth would. Nobody's patience is
         * spent converging.
         */
        const val TAKES = 12

        /** Bisection steps. Six resolves the cutoff to about 0.01. */
        private const val SEARCH_STEPS = 6

        private const val GET_READY_MS = 700L

        /** The "now" beep, plus the speaker's output latency with room to spare. */
        private const val CUE_CLEAR_MS = TuningCues.BEEP_MS + 150L

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
         * The prompts for one session: the phrase and a decoy, alternating.
         *
         * Alternating rather than blocked, so neither half is said in the
         * sing-song a person falls into after the fourth identical prompt.
         * Decoys are drawn fresh each session — half of them sound like the
         * wake phrase and half are ordinary sentences, because a ladder set
         * only against near-misses is tuned for a trap nobody walks into, and
         * one set only against unrelated speech has never been tested at all.
         */
        fun takeList(): List<Take> = DECOY_PROMPTS.shuffled().take(TAKES / 2).flatMap { decoy ->
            listOf(
                Take(TakeKind.PHRASE, R.string.sensitivity_tuning_prompt_phrase),
                Take(TakeKind.DECOY, decoy),
            )
        }

        /**
         * Four near-misses and four ordinary sentences. English source strings
         * only; a translator replaces them with words that collide with the
         * wake phrase in their own language, which is not something that
         * survives translation word for word.
         */
        private val DECOY_PROMPTS = listOf(
            R.string.sensitivity_tuning_decoy_hey_harry,
            R.string.sensitivity_tuning_decoy_hey_there,
            R.string.sensitivity_tuning_decoy_okay_already,
            R.string.sensitivity_tuning_decoy_hey_are_you,
            R.string.sensitivity_tuning_decoy_whats_the_time,
            R.string.sensitivity_tuning_decoy_put_the_kettle_on,
            R.string.sensitivity_tuning_decoy_see_you_tomorrow,
            R.string.sensitivity_tuning_decoy_where_did_i_put,
        )
    }
}
