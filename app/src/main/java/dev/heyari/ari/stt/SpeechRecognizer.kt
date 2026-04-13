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
    /**
     * Second sherpa stream that runs concurrently with [stream] but is fed
     * with bigger acceptWaveform batches (~1 s instead of 100 ms). Sherpa's
     * streaming decoder commits different tokens depending on how much audio
     * arrives per call — bigger calls give it more context per decoder pass
     * and sometimes catch words the streaming pass misses. Used by the
     * NotUnderstood retry path in [VoiceSession].
     */
    private var parallelStream: OnlineStream? = null
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
                modelType = model.modelType,
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
        // Don't call recognizer.release() — sherpa-onnx's finalize() also
        // frees native memory, and release() doesn't guard against that.
        // On hardened allocators (GrapheneOS) the double free is fatal.
        // Nulling the reference lets the GC handle cleanup via finalize().
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
        parallelStream = rec.createStream()
        Log.d(TAG, "Streams created (main + parallel)")

        _state.value = SttState.Listening("")
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "STT listening started (rewindSeconds=$rewindSeconds)")

        listenJob = scope.launch {
            val currentStream = stream ?: return@launch
            val parStream = parallelStream
            var firstChunkLogged = false
            // Sherpa-onnx zipformer is tuned for ~100ms chunks. The producer
            // (WakeWordService) writes 10ms chunks because microWakeWord wants
            // 10ms feature steps, so we re-batch on the consumer side. Without
            // this, sherpa decodes 10× per 100ms and transcription quality
            // collapses (e.g. "tell me some wisdom" → "Team").
            val batchAccumulator = ArrayList<ShortArray>(16)
            var batchSamples = 0
            // Parallel stream batching: ~1 second chunks, and ONLY live audio
            // (no pre-roll). The pre-roll feeds silence + wake phrase into
            // the main stream, biasing its decoder ("Okay Ari,"). The
            // parallel stream skips that entirely: different encoder start
            // state → different token commits → a meaningfully different
            // second opinion. We skip pre-roll by ignoring the first
            // PREROLL_SKIP_BATCHES batches (= pre-roll slices / main batch
            // target size ≈ 20 batches of 1600 samples = 32000 samples).
            val parBatchAccumulator = ArrayList<ShortArray>(160)
            var parBatchSamples = 0
            var mainBatchCount = 0
            // Raw PCM accumulator for the offline retry fallback.
            val audioAccum = ArrayList<ShortArray>(200)
            var audioAccumSamples = 0
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

                    // Stash raw PCM for the offline retry fallback.
                    audioAccum.add(merged.copyOf())
                    audioAccumSamples += merged.size

                    val floatBuffer = FloatArray(merged.size) { i -> merged[i] / 32768.0f }
                    currentStream.acceptWaveform(floatBuffer, SAMPLE_RATE)

                    while (rec.isReady(currentStream)) {
                        rec.decode(currentStream)
                    }

                    // Feed LIVE audio (post-pre-roll) into the parallel
                    // stream. Skip the first N batches which are the pre-roll
                    // (silence + wake phrase). The parallel stream starts
                    // clean with only the user's actual speech, producing a
                    // meaningfully different decode that can rescue a bad
                    // streaming commit.
                    mainBatchCount++
                    if (parStream != null && mainBatchCount > PREROLL_SKIP_BATCHES) {
                        parBatchAccumulator.add(merged)
                        parBatchSamples += merged.size
                        if (parBatchSamples >= PARALLEL_BATCH_TARGET_SAMPLES) {
                            val parMerged = ShortArray(parBatchSamples)
                            var ppos = 0
                            for (b in parBatchAccumulator) {
                                System.arraycopy(b, 0, parMerged, ppos, b.size)
                                ppos += b.size
                            }
                            parBatchAccumulator.clear()
                            parBatchSamples = 0
                            val parFloat = FloatArray(parMerged.size) { i -> parMerged[i] / 32768.0f }
                            parStream.acceptWaveform(parFloat, SAMPLE_RATE)
                            while (rec.isReady(parStream)) {
                                rec.decode(parStream)
                            }
                        }
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
                        // Flush any leftover audio into the parallel stream,
                        // then finalise it so its decoder commits everything
                        // it has. Read its result for the NotUnderstood
                        // retry path.
                        val parallelText = parStream?.let { ps ->
                            try {
                                if (parBatchSamples > 0) {
                                    val tail = ShortArray(parBatchSamples)
                                    var tpos = 0
                                    for (b in parBatchAccumulator) {
                                        System.arraycopy(b, 0, tail, tpos, b.size)
                                        tpos += b.size
                                    }
                                    parBatchAccumulator.clear()
                                    parBatchSamples = 0
                                    val tailFloat = FloatArray(tail.size) { i -> tail[i] / 32768.0f }
                                    ps.acceptWaveform(tailFloat, SAMPLE_RATE)
                                }
                                ps.inputFinished()
                                while (rec.isReady(ps)) {
                                    rec.decode(ps)
                                }
                                val parRaw = rec.getResult(ps).text.trim()
                                val parCleaned = stripWakePhrase(parRaw)
                                Log.i(TAG, "Parallel stream final: raw='$parRaw' cleaned='$parCleaned'")
                                parCleaned.takeIf { it.isNotEmpty() && it != cleanedPartial }
                            } catch (t: Throwable) {
                                Log.w(TAG, "Parallel stream finalisation failed", t)
                                null
                            }
                        }
                        // Merge the raw PCM accumulator into one flat array
                        // for the offline retry fallback.
                        val mergedAudio = ShortArray(audioAccumSamples)
                        var apos = 0
                        for (a in audioAccum) {
                            System.arraycopy(a, 0, mergedAudio, apos, a.size)
                            apos += a.size
                        }
                        _state.value = SttState.Done(cleanedPartial, parallelText, mergedAudio)
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
        recognizer = null
        loadedModelId = null
        Log.i(TAG, "STT recognizer released")
    }

    /**
     * Feed [audio] into a brand-new sherpa stream in one shot, call
     * `inputFinished()` to force the decoder to commit, and return the
     * cleaned transcript. Returns null if the recognizer isn't loaded or
     * the result is empty after wake-phrase stripping.
     *
     * This is the "third layer" retry: slower (blocks for the full
     * decode) but structurally different from both the streaming pass
     * and the parallel pass — the decoder sees the entire utterance
     * before committing any token, giving it maximum context.
     *
     * MUST be called from a background thread.
     */
    fun transcribeOffline(audio: ShortArray): String? {
        val rec = recognizer ?: return null
        val offStream = rec.createStream()
        return try {
            val floats = FloatArray(audio.size) { i -> audio[i] / 32768.0f }
            offStream.acceptWaveform(floats, SAMPLE_RATE)
            offStream.inputFinished()
            while (rec.isReady(offStream)) {
                rec.decode(offStream)
            }
            val raw = rec.getResult(offStream).text.trim()
            val cleaned = stripWakePhrase(raw)
            Log.i(TAG, "Offline retry: raw='$raw' cleaned='$cleaned'")
            cleaned.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Log.w(TAG, "Offline retry failed", t)
            null
        } finally {
            offStream.release()
        }
    }

    private fun stopRecording() {
        listenJob?.cancel()
        listenJob = null
        captureBus.disarm()
        stream?.release()
        stream = null
        parallelStream?.release()
        parallelStream = null
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
        // 1 s at 16 kHz — chunk size for the parallel stream. Bigger
        // batches give the decoder more context per acceptWaveform call.
        private const val PARALLEL_BATCH_TARGET_SAMPLES = 16000
        // How many main-stream batches to skip before feeding the parallel
        // stream. Skips the pre-roll (silence + wake phrase) so the
        // parallel decoder starts clean on user speech only.
        // 32000 pre-roll samples / 1600 batch target = 20 batches.
        private const val PREROLL_SKIP_BATCHES = 20
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
    /**
     * @param text Best transcript from the streaming decoder.
     * @param parallel Transcript from a second sherpa stream that ran in
     *   parallel with bigger acceptWaveform batches — sometimes commits
     *   different (better) tokens because the decoder gets more context
     *   per call. May be null if the parallel decode failed or was empty.
     *   When the engine doesn't understand [text], the host should try
     *   feeding [parallel] before giving up.
     */
    /**
     * @param text Best transcript from the streaming decoder.
     * @param parallel Transcript from the parallel clean-start stream,
     *   or null if it was identical to [text] or empty.
     * @param audio Raw 16-bit PCM of the entire captured utterance. The
     *   host can feed this into [SpeechRecognizer.transcribeOffline] for
     *   a third-layer retry if both [text] and [parallel] fail.
     */
    data class Done(
        val text: String,
        val parallel: String? = null,
        val audio: ShortArray? = null,
    ) : SttState
    data class Error(val message: String) : SttState
}
