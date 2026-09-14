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
) {
    sealed interface State {
        data object Idle : State

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

        val attempts = ArrayList<ShortArray>(ATTEMPTS)
        for (index in 1..ATTEMPTS) {
            _state.value = State.GetReady(index, ATTEMPTS)
            delay(GET_READY_MS)
            attempts += listen(channel, index)
        }
        releaseBus()

        _state.value = State.Scoring
        val buffer = loadModel(model.assetFilename) ?: return null
        return TunedSpeaker(
            name = name,
            attempts = attempts.size,
            heardAt = WakeWordSensitivity.entries.associateWith { level ->
                attempts.count { heard(it, buffer, model, level) }
            },
        )
    }

    private suspend fun listen(channel: Channel<ShortArray>, index: Int): ShortArray {
        val endsAt = SystemClock.elapsedRealtime() + LISTEN_MS
        val chunks = ArrayList<ShortArray>()
        var samples = 0
        while (SystemClock.elapsedRealtime() < endsAt) {
            val chunk = channel.receiveCatching().getOrNull() ?: break
            chunks += chunk
            samples += chunk.size
            _state.value = State.Listening(index, ATTEMPTS, levelOf(chunk))
        }
        val pcm = ShortArray(samples)
        var offset = 0
        for (part in chunks) {
            part.copyInto(pcm, offset)
            offset += part.size
        }
        return pcm
    }

    /**
     * Whether [level] would have woken Ari on this attempt.
     *
     * A fresh engine every time, and that is not defensive: [MicroWakeWord.reset]
     * clears the frontend and the detection window but leaves the TFLite
     * interpreter's variable tensors alone, so a reused engine scores attempt
     * five with attempt four still inside it. The same defect in the offline
     * evaluator made a ten-minute recording open at 0.02 from cold and at 0.9961
     * straight after a wake phrase.
     */
    private fun heard(
        pcm: ShortArray,
        modelBuffer: ByteBuffer,
        model: WakeWordModel,
        level: WakeWordSensitivity,
    ): Boolean = MicroWakeWord(
        modelBuffer = modelBuffer,
        featureStepSizeMs = model.featureStepSizeMs,
        probabilityCutoff = model.operatingPoint(level).probabilityCutoff,
        slidingWindowSize = model.operatingPoint(level).slidingWindowSize,
    ).use { engine ->
        var detected = false
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + CHUNK_SIZE, pcm.size)
            if (engine.processAudio(pcm.copyOfRange(offset, end))) detected = true
            offset = end
        }
        detected
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
        private const val LISTEN_MS = 2_500L
        private const val AUDIO_TIMEOUT_MS = 1_000L
        private const val CHUNK_SIZE = 480
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
