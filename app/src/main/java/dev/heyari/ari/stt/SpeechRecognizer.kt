package dev.heyari.ari.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.heyari.ari.audio.CaptureBus
import dev.heyari.ari.voice.stripWakePhrase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class SpeechRecognizer(private val captureBus: CaptureBus) {

    private var recognizer: OnlineRecognizer? = null
    private var loadedModelId: String? = null
    private var stream: OnlineStream? = null
    private var listenJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow<SttState>(SttState.Idle)
    val state: StateFlow<SttState> = _state.asStateFlow()

    val isModelLoaded: Boolean
        get() = recognizer != null

    val currentModelId: String?
        get() = loadedModelId

    /**
     * Loads a model from the given directory. Files inside [modelDir] must match
     * the [SttModel.encoderFile] / [decoderFile] / [joinerFile] / [tokensFile] names.
     * Releases any previously loaded model.
     */
    fun loadModel(model: SttModel, modelDir: File) {
        if (loadedModelId == model.id && recognizer != null) {
            Log.d(TAG, "Model ${model.id} already loaded")
            return
        }

        unload()

        val encoder = File(modelDir, model.encoderFile)
        val decoder = File(modelDir, model.decoderFile)
        val joiner = File(modelDir, model.joinerFile)
        val tokens = File(modelDir, model.tokensFile)

        require(encoder.isFile && decoder.isFile && joiner.isFile && tokens.isFile) {
            "Model files missing in ${modelDir.absolutePath}"
        }

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0.0f),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                tokens = tokens.absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.0f, minUtteranceLength = 0.0f),
                rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.0f, minUtteranceLength = 0.0f),
                rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0.0f, minUtteranceLength = 20.0f),
            ),
            // Sherpa's energy-based endpoint detection is disabled because
            // it freezes the stream the moment it fires, even if we don't
            // call reset(). And reset() destroys the encoder context, which
            // clips the next ~500ms of speech. We do our own endpointing
            // based on cleaned-partial-text stability — see the decode loop
            // in startListening().
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        )

        Log.i(TAG, "Loading sherpa-onnx model: ${model.id}")
        val loaded = OnlineRecognizer(assetManager = null, config = config)
        recognizer = loaded
        loadedModelId = model.id
        Log.i(TAG, "Model loaded: ${model.id}")

        // Warm up the recognizer: the first decode on a freshly-loaded
        // zipformer triggers graph setup, XNNPACK delegate init, and tensor
        // arena allocation — on a phone CPU that's easily 2–5 seconds of
        // blocking work. We pay that cost here (on the loading thread,
        // typically IO under the splash) instead of on the first word after
        // the wake beep, which was eating the first ~5 seconds of user
        // speech while the read loop stalled and AudioRecord overflowed.
        val warmupStart = System.currentTimeMillis()
        val warmupStream = loaded.createStream()
        try {
            val silence = FloatArray(SAMPLE_RATE / 5) // 200ms of silence
            warmupStream.acceptWaveform(silence, SAMPLE_RATE)
            while (loaded.isReady(warmupStream)) {
                loaded.decode(warmupStream)
            }
        } finally {
            warmupStream.release()
        }
        Log.i(TAG, "Recognizer warmed in ${System.currentTimeMillis() - warmupStart}ms")
    }

    fun unload() {
        stopRecording()
        recognizer?.release()
        recognizer = null
        loadedModelId = null
    }

    /**
     * Begin listening for speech. Subscribes to [CaptureBus] (which is
     * already capturing 24/7 via the wake word service) and consumes a
     * pre-roll snapshot of the last [rewindSeconds] seconds plus the live
     * stream that follows. No `AudioRecord` is opened here — the mic stays
     * with the wake word service for the entire app lifecycle.
     */
    fun startListening(rewindSeconds: Float = DEFAULT_REWIND_SECONDS) {
        val rec = recognizer ?: run {
            Log.e(TAG, "startListening called but no model loaded")
            _state.value = SttState.Error("No STT model loaded. Configure one in Settings.")
            return
        }
        if (_state.value is SttState.Listening) return

        val channel = captureBus.arm(rewindSeconds) ?: run {
            Log.e(TAG, "CaptureBus already armed — refusing to start listening")
            _state.value = SttState.Error("Audio bus busy")
            return
        }

        stream = rec.createStream()
        Log.d(TAG, "Stream created")

        _state.value = SttState.Listening("")
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "STT listening started (rewindSeconds=$rewindSeconds)")

        listenJob = scope.launch {
            val currentStream = stream ?: return@launch
            var firstChunkLogged = false
            // Sherpa-onnx zipformer is tuned for ~100ms chunks. The producer
            // (WakeWordService) writes 10ms chunks because microWakeWord wants
            // 10ms feature steps, so we re-batch on the consumer side. Without
            // this, sherpa decodes 10× per 100ms and transcription quality
            // collapses (e.g. "tell me some wisdom" → "Team").
            val batchAccumulator = ArrayList<ShortArray>(16)
            var batchSamples = 0
            // Endpoint detector: track when cleanedPartial last changed.
            // When it stays at the same NON-EMPTY value for STABILITY_WINDOW_MS,
            // assume the user has stopped speaking and emit Done.
            //
            // Why this and not RMS / inputFinished: sherpa-onnx's streaming
            // decoder lags audio by an unpredictable amount on-device, and
            // calling inputFinished() does NOT force it to commit late
            // tokens — once it decides a hypothesis is "final", it's final.
            // Audio-energy detection fires too early because sherpa can be
            // 500-1000ms behind real time when the user stops speaking.
            // Partial-text stability is the least-bad signal we have.
            var lastCleaned = ""
            var lastChangeAt = System.currentTimeMillis()
            try {
                while (isActive) {
                    val incoming = try {
                        channel.receive()
                    } catch (e: ClosedReceiveChannelException) {
                        return@launch
                    }
                    if (incoming.isNotEmpty()) {
                        batchAccumulator.add(incoming)
                        batchSamples += incoming.size
                    }
                    // Wait until we have at least one full sherpa chunk before
                    // feeding. The pre-roll is already 32000 samples so the
                    // first iteration always flushes immediately.
                    if (batchSamples < BATCH_TARGET_SAMPLES) continue

                    val merged = ShortArray(batchSamples)
                    var pos = 0
                    for (b in batchAccumulator) {
                        System.arraycopy(b, 0, merged, pos, b.size)
                        pos += b.size
                    }
                    batchAccumulator.clear()
                    batchSamples = 0

                    if (!firstChunkLogged) {
                        Log.i(TAG, "First chunk decoded ${System.currentTimeMillis() - startTime}ms after arm (size=${merged.size})")
                        firstChunkLogged = true
                    }

                    val floatBuffer = FloatArray(merged.size) { i -> merged[i] / 32768.0f }
                    currentStream.acceptWaveform(floatBuffer, SAMPLE_RATE)

                    while (rec.isReady(currentStream)) {
                        rec.decode(currentStream)
                    }

                    val rawPartial = rec.getResult(currentStream).text.trim()
                    val cleanedPartial = stripWakePhrase(rawPartial)
                    Log.d(TAG, "decode: fed=${merged.size} raw='$rawPartial' cleaned='$cleanedPartial'")
                    if (cleanedPartial.isNotEmpty()) {
                        _state.value = SttState.Listening(cleanedPartial)
                    }

                    val now = System.currentTimeMillis()
                    if (cleanedPartial != lastCleaned) {
                        lastCleaned = cleanedPartial
                        lastChangeAt = now
                    } else if (cleanedPartial.isNotEmpty() &&
                        now - lastChangeAt >= STABILITY_WINDOW_MS) {
                        Log.i(TAG, "Custom endpoint: stable for ${now - lastChangeAt}ms cleaned='$cleanedPartial'")
                        _state.value = SttState.Done(cleanedPartial)
                        stopRecording()
                        return@launch
                    }
                }
            } finally {
                // If the loop exits for any reason and we're still armed, the
                // stopRecording() path below handles disarming. Defensive only.
            }
        }
    }

    fun stopListening() {
        val current = _state.value
        stopRecording()
        if (current is SttState.Listening && current.partial.isNotEmpty()) {
            _state.value = SttState.Done(current.partial)
        } else {
            _state.value = SttState.Idle
        }
    }

    fun reset() {
        _state.value = SttState.Idle
    }

    fun release() {
        stopRecording()
        scope.cancel()
        recognizer?.release()
        recognizer = null
        loadedModelId = null
        Log.i(TAG, "STT recognizer released")
    }

    private fun stopRecording() {
        listenJob?.cancel()
        listenJob = null
        captureBus.disarm()
        stream?.release()
        stream = null
    }

    companion object {
        private const val TAG = "SpeechRecognizer"
        private const val SAMPLE_RATE = 16000
        // Generous rewind: capture the full ring (~2 s) so device-power
        // variations in arm latency never clip the user's first word. The
        // wake phrase that inevitably comes along for the ride is stripped
        // from the transcript by stripWakePhrase(), and endpoint detection
        // is gated on the stripped text — so the wake phrase never reaches
        // the engine and never causes a premature endpoint.
        private const val DEFAULT_REWIND_SECONDS = 2.0f
        // 100 ms at 16 kHz — matches the chunk size sherpa was originally
        // tuned against in the legacy SpeechRecognizer read loop.
        private const val BATCH_TARGET_SAMPLES = 1600
        // How long the cleaned partial must hold steady before we declare
        // the user is done speaking. 1500 ms is the empirical sweet spot —
        // longer doesn't help (sherpa's streaming decoder either commits
        // late tokens within ~1s or never does) and slows responses.
        // Remaining flakiness is sherpa-onnx model lag, not this value.
        private const val STABILITY_WINDOW_MS = 1500L
    }
}

sealed interface SttState {
    data object Idle : SttState
    data class Listening(val partial: String) : SttState
    data class Done(val text: String) : SttState
    data class Error(val message: String) : SttState
}
