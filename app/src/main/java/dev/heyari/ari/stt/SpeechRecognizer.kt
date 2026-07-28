package dev.heyari.ari.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.heyari.ari.audio.CaptureBus
import dev.heyari.ari.locale.AriFfiLocaleProvider
import dev.heyari.ari.voice.WakeMatch
import dev.heyari.ari.voice.matchWakePhrase
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
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Two-mode STT recogniser:
 *
 * - **Online streaming** (Kroko, Nemotron — Zipformer transducers): partials emitted
 *   as the user speaks, custom partial-text-stability endpointing, parallel-stream
 *   second opinion. The English path.
 *
 * - **Offline buffered** (Whisper-turbo — encoder-decoder): no partials, RMS-based
 *   silence-detection endpointing, full-utterance decode in one shot at end of speech.
 *   The path for every non-English language.
 *
 * Dispatch on [SttModel.modelType] at [loadModel] time. Callers (VoiceSession,
 * onboarding flow) don't need to know which path is active — both produce the
 * same [SttState.Done] terminal event. The retry layers in `VoiceSession` skip
 * automatically for the offline path because [SttState.Done.parallel] and
 * [SttState.Done.audio] are both null when whisper produces the result (no
 * second decoder to compare against, no point retrying with a model that
 * already saw the full utterance).
 */
@Singleton
class SpeechRecognizer @Inject constructor(
    private val captureBus: CaptureBus,
    private val localeProvider: AriFfiLocaleProvider,
) {

    // --- Online streaming path state ---
    private var onlineRecognizer: OnlineRecognizer? = null
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

    // --- Offline whisper path state ---
    private var offlineRecognizer: OfflineRecognizer? = null
    /**
     * Locale that [offlineRecognizer] was constructed with. Whisper bakes the
     * target language into the recogniser config; if the user changes locale
     * we need to dispose and reload. Tracked here so [loadModel] can compare
     * against the current locale and force a reload when they diverge.
     */
    private var offlineLocale: String? = null

    // --- Shared state ---
    private var loadedModelId: String? = null
    private var listenJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    /**
     * Serialises [loadModel] so a flurry of concurrent callers (multiple
     * Hilt-injected ViewModels each reacting to an `activeSttModelId`
     * change at the same moment) doesn't load the same ~1 GB model
     * multiple times in parallel — each parallel load instantiates a
     * fresh native ONNX runtime, and on a Whisper-turbo install that
     * was tipping the emulator into memory-pressure-event territory
     * and getting the WakeWordService killed.
     *
     * Callers waiting on the lock see the model already loaded once
     * the first one completes and short-circuit out of the load.
     */
    private val loadLock = Any()

    private val _state = MutableStateFlow<SttState>(SttState.Idle)
    val state: StateFlow<SttState> = _state.asStateFlow()

    val isModelLoaded: Boolean
        get() = onlineRecognizer != null || offlineRecognizer != null

    /**
     * True when the loaded recogniser is the online streaming one. Hosts need
     * this to know whether [SttState.Listening] partials are a usable liveness
     * signal: the streaming path emits one per decode, whereas the offline
     * whisper path emits `Listening("")` once at arm and then nothing at all
     * until [SttState.Transcribing]. A host timing out on "no partials" would
     * therefore cut every offline utterance short.
     */
    val isStreaming: Boolean
        get() = onlineRecognizer != null

    val currentModelId: String?
        get() = loadedModelId

    /**
     * Loads a model from the given directory. Files inside [modelDir] must match
     * the [SttModel.encoderFile] / [decoderFile] / [joinerFile] / [tokensFile] names.
     * Releases any previously loaded model.
     *
     * Dispatch on [SttModel.modelType]: zipformer / zipformer2 → online streaming
     * recogniser; whisper → offline encoder-decoder recogniser. For whisper the
     * recogniser is constructed against the user's currently-active locale, and
     * a locale change between calls forces a reload (whisper bakes the language
     * into its config — there's no per-decode override).
     */
    fun loadModel(model: SttModel, modelDir: File) {
        // Fast path — no lock contention if the model is already loaded
        // with the right config. Multiple concurrent callers will all
        // hit this once the first one has finished its load.
        if (isLoadedWithMatchingConfig(model)) {
            Log.d(TAG, "Model ${model.id} already loaded (fast path)")
            return
        }

        synchronized(loadLock) {
            // Re-check inside the lock: another caller may have completed
            // the load while this one was waiting. Without this re-check
            // we'd unload-and-reload immediately after a sibling finished,
            // wasting the same ~3-10 s of warmup we just paid.
            if (isLoadedWithMatchingConfig(model)) {
                Log.d(TAG, "Model ${model.id} already loaded (after lock)")
                return
            }

            unload()

            when (model.modelType) {
                "whisper" -> loadOfflineWhisperModel(model, modelDir)
                "zipformer", "zipformer2" -> loadOnlineModel(model, modelDir)
                else -> throw IllegalArgumentException(
                    "Unknown STT modelType: ${model.modelType} (id=${model.id})",
                )
            }
        }
    }

    private fun isLoadedWithMatchingConfig(model: SttModel): Boolean {
        if (loadedModelId != model.id || !isModelLoaded) return false
        // Online transducer models have no per-locale config — id match
        // is sufficient.
        if (model.modelType != "whisper") return true
        // Whisper bakes the language into its recogniser config. A locale
        // change requires a fresh load even when the model id matches.
        return offlineLocale == localeProvider.currentLocale()
    }

    private fun loadOnlineModel(model: SttModel, modelDir: File) {
        val encoder = File(modelDir, model.encoderFile)
        val decoder = File(modelDir, model.decoderFile)
        val joinerName = model.joinerFile
            ?: error("Online transducer model ${model.id} has null joinerFile")
        val joiner = File(modelDir, joinerName)
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
            // in startOnlineListening().
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        )

        Log.i(TAG, "Loading sherpa-onnx online model: ${model.id}")
        val loaded = OnlineRecognizer(assetManager = null, config = config)
        onlineRecognizer = loaded
        loadedModelId = model.id
        Log.i(TAG, "Online model loaded: ${model.id}")

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
        Log.i(TAG, "Online recogniser warmed in ${System.currentTimeMillis() - warmupStart}ms")
    }

    private fun loadOfflineWhisperModel(model: SttModel, modelDir: File) {
        val encoder = File(modelDir, model.encoderFile)
        val decoder = File(modelDir, model.decoderFile)
        val tokens = File(modelDir, model.tokensFile)

        require(encoder.isFile && decoder.isFile && tokens.isFile) {
            "Whisper model files missing in ${modelDir.absolutePath}"
        }

        val locale = localeProvider.currentLocale()
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0.0f),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    language = locale,
                    task = "transcribe",
                ),
                tokens = tokens.absolutePath,
                modelType = "whisper",
                numThreads = 2,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )

        Log.i(TAG, "Loading sherpa-onnx whisper model: ${model.id} (lang=$locale)")
        val loaded = OfflineRecognizer(assetManager = null, config = config)
        offlineRecognizer = loaded
        offlineLocale = locale
        loadedModelId = model.id
        Log.i(TAG, "Whisper model loaded: ${model.id} (lang=$locale)")

        // Warm up the recogniser: first whisper decode pays graph init +
        // tensor arena allocation, ~2-4s on a phone CPU. Burn it here on
        // the loading thread rather than on the first user utterance.
        val warmupStart = System.currentTimeMillis()
        val warmupStream = loaded.createStream()
        try {
            val silence = FloatArray(SAMPLE_RATE) // 1 s of silence
            warmupStream.acceptWaveform(silence, SAMPLE_RATE)
            loaded.decode(warmupStream)
            // Drop the result — warm-up only. Whisper will hallucinate on
            // pure silence ("[BLANK_AUDIO]" / "(silence)" / etc.) and we
            // don't care.
            loaded.getResult(warmupStream)
        } finally {
            warmupStream.release()
        }
        Log.i(TAG, "Whisper recogniser warmed in ${System.currentTimeMillis() - warmupStart}ms")
    }

    fun unload() {
        stopRecording()
        // Don't call recognizer.release() — sherpa-onnx's finalize() also
        // frees native memory, and release() doesn't guard against that.
        // On hardened allocators (GrapheneOS) the double free is fatal.
        // Nulling the reference lets the GC handle cleanup via finalize().
        onlineRecognizer = null
        offlineRecognizer = null
        offlineLocale = null
        loadedModelId = null
    }

    /**
     * Begin listening for speech. Subscribes to [CaptureBus] (which is
     * already capturing 24/7 via the wake word service) and consumes a
     * pre-roll snapshot of the last [rewindSeconds] seconds plus the live
     * stream that follows. No `AudioRecord` is opened here — the mic stays
     * with the wake word service for the entire app lifecycle.
     *
     * Dispatches to the streaming or buffered code path based on which
     * recogniser is loaded.
     */
    fun startListening(rewindSeconds: Float = DEFAULT_REWIND_SECONDS) {
        if (_state.value is SttState.Listening) return

        val online = onlineRecognizer
        val offline = offlineRecognizer

        when {
            online != null -> startOnlineListening(online, rewindSeconds)
            offline != null -> startOfflineListening(offline, rewindSeconds)
            else -> {
                Log.e(TAG, "startListening called but no model loaded")
                _state.value = SttState.Error("No STT model loaded. Configure one in Settings.")
            }
        }
    }

    private fun startOnlineListening(rec: OnlineRecognizer, rewindSeconds: Float) {
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
        Log.i(TAG, "STT (online) listening started (rewindSeconds=$rewindSeconds)")

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
                    val locale = localeProvider.currentLocale()
                    val partialMatch = matchWakePhrase(rawPartial, locale)
                    val cleanedPartial = partialMatch.text
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
                                val parCleaned = stripWakePhrase(parRaw, localeProvider.currentLocale())
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
                        _state.value = SttState.Done(
                            cleanedPartial,
                            parallelText,
                            mergedAudio,
                            rawPartial,
                            wakeVerdict(partialMatch, locale),
                        )
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

    private fun startOfflineListening(rec: OfflineRecognizer, rewindSeconds: Float) {
        val channel = captureBus.arm(rewindSeconds) ?: run {
            Log.e(TAG, "CaptureBus already armed — refusing to start listening")
            _state.value = SttState.Error("Audio bus busy")
            return
        }

        _state.value = SttState.Listening("")
        Log.i(TAG, "STT (offline whisper) listening started (rewindSeconds=$rewindSeconds)")

        listenJob = scope.launch {
            // Buffer everything. Whisper has no streaming partials and no
            // per-chunk decode — we just accumulate audio until the silence
            // detector says the user is done, then run one decode.
            val accumulator = ArrayList<ShortArray>(200)
            var totalSamples = 0
            // RMS-based silence detector. Cheap and self-contained — no
            // separate VAD model file to download. Refinements (silero VAD
            // for noisy environments) would be a Phase-2.5 polish item.
            //
            // We require at least one chunk above SPEECH_RMS_THRESHOLD
            // before considering an endpoint, otherwise we'd fire the moment
            // the user pauses at the start to think. Once speech has
            // started, MIN_SILENCE_AFTER_SPEECH_MS of below-threshold audio
            // ends the utterance.
            var firstSpeechAt: Long? = null
            var lastSpeechAt = System.currentTimeMillis()

            try {
                while (isActive) {
                    val incoming = try {
                        channel.receive()
                    } catch (e: ClosedReceiveChannelException) {
                        return@launch
                    }
                    if (incoming.isEmpty()) continue

                    accumulator.add(incoming)
                    totalSamples += incoming.size

                    val now = System.currentTimeMillis()
                    val rms = computeRms(incoming)
                    if (rms >= SPEECH_RMS_THRESHOLD) {
                        if (firstSpeechAt == null) {
                            firstSpeechAt = now
                            Log.d(TAG, "Offline: speech started (rms=$rms)")
                        }
                        lastSpeechAt = now
                    }

                    val firstAt = firstSpeechAt
                    if (firstAt != null) {
                        val sinceLastSpeech = now - lastSpeechAt
                        val totalDuration = now - firstAt
                        if (sinceLastSpeech >= MIN_SILENCE_AFTER_SPEECH_MS &&
                            totalDuration >= MIN_UTTERANCE_MS
                        ) {
                            Log.i(
                                TAG,
                                "Offline endpoint: silence for ${sinceLastSpeech}ms after ${totalDuration}ms of utterance",
                            )
                            decodeWhisperAndEmit(rec, accumulator, totalSamples)
                            return@launch
                        }
                    }

                    if (totalSamples >= MAX_OFFLINE_UTTERANCE_SAMPLES) {
                        Log.i(TAG, "Offline endpoint: hard cap (${totalSamples} samples)")
                        decodeWhisperAndEmit(rec, accumulator, totalSamples)
                        return@launch
                    }
                }
            } finally {
                // stopRecording() handles disarm when the job is cancelled
                // externally. Defensive only.
            }
        }
    }

    private suspend fun decodeWhisperAndEmit(
        rec: OfflineRecognizer,
        accumulator: List<ShortArray>,
        totalSamples: Int,
    ) {
        val merged = ShortArray(totalSamples)
        var pos = 0
        for (b in accumulator) {
            System.arraycopy(b, 0, merged, pos, b.size)
            pos += b.size
        }

        // Hand-off signal between "we heard you stop talking" and "we
        // have a transcript". Posted before the (CPU-heavy) decode so
        // VoiceSession can flip its overlay to Thinking — otherwise
        // users see a still-listening UI for the whole decode window.
        _state.value = SttState.Transcribing

        // Decode is CPU-bound (whisper-turbo int8 takes ~300-700ms on a
        // mid-tier phone for a 3-5s utterance). Push to the Default
        // dispatcher so we don't hold the listening coroutine on whatever
        // thread it currently runs on.
        val transcript = withContext(Dispatchers.Default) {
            val s = rec.createStream()
            try {
                val floats = FloatArray(merged.size) { i -> merged[i] / 32768.0f }
                s.acceptWaveform(floats, SAMPLE_RATE)
                rec.decode(s)
                rec.getResult(s).text.trim()
            } finally {
                s.release()
            }
        }

        val locale = localeProvider.currentLocale()
        val match = matchWakePhrase(transcript, locale)
        Log.i(TAG, "Whisper transcript: raw='$transcript' cleaned='${match.text}'")

        // No parallel stream, no audio-for-retry: whisper is the final
        // word. The retry layers in VoiceSession skip on null.
        _state.value = SttState.Done(
            text = match.text,
            parallel = null,
            audio = null,
            raw = transcript,
            nameMatched = wakeVerdict(match, locale),
        )
        stopRecording()
    }

    /**
     * The wake-verification verdict carried by [SttState.Done.nameMatched] —
     * [match]'s verdict for English, null for every other locale.
     *
     * The wake-word model is English-only whatever Ari's active locale is, but
     * sherpa is not: a non-English recogniser transcribes the English phrase
     * through its own phonotactics. The name list `matchWakePhrase` checks was
     * built empirically from ENGLISH sherpa mishears and `WakeMishearTable` is
     * still empty for every other language, so outside English we have no
     * evidence about what "hey ari" actually comes out as. Acting on a verdict
     * we can't trust would turn a benign failure (wake phrase left in the
     * query, engine says "not understood") into a hard one (turn silently
     * dismissed) — so we don't form one, and `shouldAcceptWake` fails open on
     * null. Same call the router already makes in `routerSupportsLocale`.
     *
     * [SttState.Done.raw] is still populated for every locale: the Italian
     * transcripts accruing in the logs are how `WakeMishearTable` eventually
     * gets filled in.
     */
    private fun wakeVerdict(match: WakeMatch, locale: String): Boolean? =
        if (locale == "en") match.nameMatched else null

    private fun computeRms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSquares = 0.0
        for (s in samples) {
            val v = s.toDouble()
            sumSquares += v * v
        }
        return sqrt(sumSquares / samples.size).toFloat()
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
        onlineRecognizer = null
        offlineRecognizer = null
        offlineLocale = null
        loadedModelId = null
        Log.i(TAG, "STT recogniser released")
    }

    /**
     * Feed [audio] into a brand-new sherpa stream in one shot, call
     * `inputFinished()` to force the decoder to commit, and return the
     * cleaned transcript. Returns null if no online recognizer is loaded
     * (e.g. whisper is loaded instead — the offline path produces its own
     * full-buffer decode in the listening loop, no extra retry layer
     * needed) or the result is empty after wake-phrase stripping.
     *
     * This is the "third layer" retry for the streaming path: slower
     * (blocks for the full decode) but structurally different from both
     * the streaming pass and the parallel pass — the decoder sees the
     * entire utterance before committing any token, giving it maximum
     * context.
     *
     * MUST be called from a background thread.
     */
    fun transcribeOffline(audio: ShortArray): String? {
        val rec = onlineRecognizer ?: return null
        val offStream = rec.createStream()
        return try {
            val floats = FloatArray(audio.size) { i -> audio[i] / 32768.0f }
            offStream.acceptWaveform(floats, SAMPLE_RATE)
            offStream.inputFinished()
            while (rec.isReady(offStream)) {
                rec.decode(offStream)
            }
            val raw = rec.getResult(offStream).text.trim()
            val cleaned = stripWakePhrase(raw, localeProvider.currentLocale())
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

        // --- Offline whisper endpointing constants ---
        // RMS threshold for "this chunk contains speech". int16 PCM samples
        // span [-32768, 32767]; quiet speech sits around 1000-3000 RMS,
        // ambient room noise typically <300. 600 lands comfortably between.
        // Tune downward if quiet talkers are dropping out, upward if room
        // noise is triggering false speech detection.
        private const val SPEECH_RMS_THRESHOLD = 600.0f
        // After at least one chunk above SPEECH_RMS_THRESHOLD has been
        // seen, this much continuous below-threshold audio ends the
        // utterance. 800 ms is roughly the same window the streaming
        // path uses for partial-text stability — keeps the perceived
        // response time consistent across both paths.
        private const val MIN_SILENCE_AFTER_SPEECH_MS = 800L
        // Don't fire the endpoint until the utterance is at least this
        // long. Stops a single noise burst followed by silence from
        // ending the session before the user has actually said anything.
        private const val MIN_UTTERANCE_MS = 500L
        // Hard cap on offline-path utterance length. Whisper's training
        // window is 30 s; longer audio gets chunked internally and the
        // first segment dominates accuracy, so we cap at the same point
        // as a safety net rather than an expected case.
        private const val MAX_OFFLINE_UTTERANCE_SAMPLES = SAMPLE_RATE * 30
    }
}

sealed interface SttState {
    data object Idle : SttState
    data class Listening(val partial: String) : SttState
    /**
     * Offline path only. Emitted between endpoint detection and the
     * whisper decode result. Lets the host flip its UI to a "thinking"
     * affordance while the model crunches — without it, the overlay
     * sits on "Listening…" for the entire decode window (~300-700 ms on
     * a phone, several seconds on an emulator), which reads as
     * unresponsive.
     *
     * The streaming Kroko/Nemotron path never emits this — its decode
     * is incremental and the final transcript is ready by the time
     * endpoint fires, so [Listening] → [Done] happens within a frame.
     */
    data object Transcribing : SttState
    /**
     * @param text Best transcript from the streaming decoder (or whisper's
     *   one-shot decode in the offline path).
     * @param parallel Transcript from the parallel clean-start stream,
     *   or null if it was identical to [text], empty, or the offline
     *   path was used (whisper has no parallel decoder).
     * @param audio Raw 16-bit PCM of the entire captured utterance, or
     *   null when the offline whisper path produced [text] (no point
     *   retrying with a model that already saw the full utterance). The
     *   host can feed non-null audio into [SpeechRecognizer.transcribeOffline]
     *   for a third-layer retry if both [text] and [parallel] fail.
     * @param raw The transcript before wake-phrase stripping, or null where
     *   only a cleaned partial survived (the manual [stopListening] path).
     *   Used for the wake-rejection log and the false-trigger capture sidecar.
     * @param nameMatched Whether [raw] contained a real wake-phrase name token.
     *   Null when no verdict could be formed — treated as "accept" downstream.
     *   Computed here rather than in the host because this class already holds
     *   the active locale that [matchWakePhrase] needs.
     */
    data class Done(
        val text: String,
        val parallel: String? = null,
        val audio: ShortArray? = null,
        val raw: String? = null,
        val nameMatched: Boolean? = null,
    ) : SttState
    data class Error(val message: String) : SttState
}
