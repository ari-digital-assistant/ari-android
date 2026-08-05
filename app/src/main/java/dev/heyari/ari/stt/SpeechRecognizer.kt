package dev.heyari.ari.stt

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.R
import dev.heyari.ari.audio.CaptureBus
import dev.heyari.ari.locale.AriFfiLocaleProvider
import dev.heyari.ari.voice.matchWakePhrase
import dev.heyari.ari.voice.stripWakePhrase
import dev.heyari.ari.voice.wakeVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

// How long the cleaned partial must hold steady before we declare the user
// is done speaking. 1500 ms is the empirical sweet spot — longer doesn't
// help (sherpa's streaming decoder either commits late tokens within ~1s or
// never does) and slows responses.
internal const val STABILITY_WINDOW_MS = 1500L

// VAD veto: the partial-stability endpoint may only fire when silero
// has also heard no speech for this long. Stability measures the
// DECODER going quiet; in heavy noise sherpa stalls mid-utterance and
// the two diverge — the two-day capture set has a clip where the user
// spoke for 2.3s past the last committed token and got amputated.
internal const val VETO_SPEECH_WINDOW_MS = 1000L

// Upper bound on the veto itself. Continuous speech-like noise — a
// television, a conversation at the next table — never lets
// msSinceLastSpeech reach VETO_SPEECH_WINDOW_MS, which would starve the
// stability arm and leave MAX_ONLINE_UTTERANCE_MS as the only way out:
// 30s of dead air where today there is 1.5s. The veto exists to outlast a
// stalled decoder, not to hand the endpoint over to the telly. 4s is a
// human-decided UX bound (2026-07-30 review), not a measured constant.
internal const val VETO_OVERRIDE_STABILITY_MS = 4000L

// Hard cap on a single online utterance, matching the offline path's
// 30s. Without it, steady noise that silero scores as speech would
// veto the endpoint indefinitely.
internal const val MAX_ONLINE_UTTERANCE_MS = 30_000L

/**
 * Whether the online decode loop should end the utterance now.
 *
 * [partialStableForMs] is how long the cleaned partial has held its current
 * value, and callers pass 0 while it is empty — an empty partial can never
 * satisfy the stability arm, but it must still be able to hit the cap.
 * [msSinceLastSpeech] is [SpeechGate.msSinceLastSpeech], or [Long.MAX_VALUE]
 * when no VAD is available, which reduces this to the stability-only
 * endpoint we shipped before the veto.
 *
 * Stability below [STABILITY_WINDOW_MS] never fires — so an empty partial,
 * which callers report as zero, can only ever leave via the cap.
 */
internal fun shouldEndpoint(
    partialStableForMs: Long,
    msSinceLastSpeech: Long,
    listeningForMs: Long,
): Boolean {
    if (listeningForMs >= MAX_ONLINE_UTTERANCE_MS) return true
    if (partialStableForMs < STABILITY_WINDOW_MS) return false
    return msSinceLastSpeech >= VETO_SPEECH_WINDOW_MS ||
        partialStableForMs >= VETO_OVERRIDE_STABILITY_MS
}

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
 * same [SttState.Done] terminal event. Neither retry layer applies to the
 * offline path: [SttState.Done.parallel] is null (no second decoder to compare
 * against) and [isStreaming] is false (no point re-decoding with a model that
 * already saw the full utterance).
 */
@Singleton
class SpeechRecognizer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val captureBus: CaptureBus,
    private val localeProvider: AriFfiLocaleProvider,
    private val cloudTranscriber: CloudTranscriber,
) {

    // --- Online streaming path state ---
    private var onlineRecognizer: OnlineRecognizer? = null
    /**
     * Silero VAD that vetoes the partial-stability endpoint while it can
     * still hear speech. Built on first listen and reused for the app's
     * lifetime — constructing one loads an ONNX model, which is far too
     * expensive to repeat per utterance. Null when the model asset is
     * missing or the native build refuses to load it, in which case
     * [shouldEndpoint] runs unvetoed.
     */
    private var speechGate: SpeechGate? = null

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
    /**
     * Guards [listenJob] handover between the threads that start and stop
     * listening — the decode loop endpointing on Dispatchers.Default, the main
     * thread dismissing a voice session, and an IO thread swapping models. All
     * three used to race on the job field and on the native stream handles
     * beside it; see [armListenJob] and [stopRecording].
     */
    private val listenLock = Any()
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

    /**
     * True when transcription is routed to [CloudTranscriber] instead of a
     * local recogniser. Set by [SttModelLoader] from the user's [SttMode];
     * kept as plain state rather than read from settings here so this class
     * stays free of DataStore and testable without it.
     */
    @Volatile
    var cloudMode: Boolean = false
        private set

    /**
     * Route transcription to the cloud (or back to a local model). Releases any
     * loaded recogniser on the way in — holding ~1 GB of ONNX for a path that
     * will never call it is exactly the memory pressure that gets the
     * wake-word service killed.
     */
    fun setCloudMode(enabled: Boolean) {
        if (cloudMode == enabled) return
        cloudMode = enabled
        if (enabled) {
            synchronized(loadLock) { unload() }
            Log.i(TAG, "STT routed to cloud; local recogniser released")
        } else {
            Log.i(TAG, "STT routed back to on-device")
        }
    }

    /** Ready to transcribe — a local model is warm, or cloud is selected
     *  (which needs nothing loaded). */
    val isModelLoaded: Boolean
        get() = cloudMode || onlineRecognizer != null || offlineRecognizer != null

    /**
     * True when the loaded recogniser is the online streaming one. Hosts need
     * this to know whether [SttState.Listening] partials are a usable liveness
     * signal: the streaming path emits one per decode, whereas the offline
     * whisper path emits `Listening("")` once at arm and then nothing at all
     * until [SttState.Transcribing]. A host timing out on "no partials" would
     * therefore cut every offline utterance short.
     */
    val isStreaming: Boolean
        get() = !cloudMode && onlineRecognizer != null

    /** Message for a cloud failure, so the user is told which wall they hit. */
    private fun cloudErrorRes(failure: CloudSttFailure): Int = when (failure) {
        CloudSttFailure.NOT_CONFIGURED -> R.string.stt_cloud_error_not_configured
        CloudSttFailure.NETWORK -> R.string.stt_cloud_error_network
        CloudSttFailure.AUTH -> R.string.stt_cloud_error_auth
        CloudSttFailure.SERVER -> R.string.stt_cloud_error_server
        CloudSttFailure.EMPTY -> R.string.stt_cloud_error_empty
    }

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
        // Don't call recognizer.release(): stopRecording() cancels the listen
        // coroutine but does not wait for it, so a decode can still be running
        // inside the native recogniser we'd be freeing. Nulling the reference
        // instead leaves the recogniser alive for exactly as long as that
        // coroutine's captured reference — the GC frees it via finalize() once
        // nobody can reach it, which is the only point at which it is safe.
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
            // Cloud first: it needs no local model, so checking it last would
            // mean a leftover recogniser from a previous on-device session
            // silently won after the user switched to cloud.
            cloudMode -> startCloudListening(rewindSeconds)
            online != null -> startOnlineListening(online, rewindSeconds)
            offline != null -> startBufferedListening(rewindSeconds, "offline whisper") { pcm ->
                decodeWhisperAndEmit(offline, pcm)
            }
            else -> {
                Log.e(TAG, "startListening called but no model loaded")
                _state.value = SttState.Error("No STT model loaded. Configure one in Settings.")
            }
        }
    }

    private fun startCloudListening(rewindSeconds: Float) {
        startBufferedListening(rewindSeconds, "cloud") { pcm ->
            _state.value = SttState.Transcribing
            val locale = localeProvider.currentLocale()
            val transcript = try {
                cloudTranscriber.transcribe(pcm, locale)
            } catch (e: CloudSttException) {
                // Say which way it failed. "Couldn't reach the server" and
                // "your key was rejected" send the user to different places,
                // and a single generic error taught nobody anything.
                Log.w(TAG, "Cloud STT failed (${e.failure})", e)
                _state.value = SttState.Error(context.getString(cloudErrorRes(e.failure)))
                return@startBufferedListening
            }
            val match = matchWakePhrase(transcript, locale)
            Log.i(TAG, "Cloud transcript: raw='$transcript' cleaned='${match.text}'")
            _state.value = SttState.Done(
                text = match.text,
                // No second decoder and no local model to re-run: a cloud
                // retry would just be the same request billed twice.
                parallel = null,
                audio = pcm,
                raw = transcript,
                nameMatched = wakeVerdict(match, locale),
            )
        }
    }

    private fun startOnlineListening(rec: OnlineRecognizer, rewindSeconds: Float) {
        val channel = captureBus.arm(rewindSeconds) ?: run {
            Log.e(TAG, "CaptureBus already armed — refusing to start listening")
            _state.value = SttState.Error("Audio bus busy")
            return
        }

        _state.value = SttState.Listening("")
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "STT (online) listening started (rewindSeconds=$rewindSeconds)")

        armListenJob(scope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext.job
            // Both streams are created, used and released entirely inside this
            // coroutine, and never published to a field. Sherpa's
            // OnlineStream.release() is just finalize(), whose "already freed?"
            // guard is a plain non-volatile read of ptr — so two threads
            // releasing the same stream both sail past it and delete the same
            // native pointer twice. Bionic's allocator tolerates that; the
            // hardened_malloc on GrapheneOS aborts the process. Sole ownership
            // by one coroutine is what makes it unreachable: stopRecording()
            // cancels the job and lets the unwind below do the freeing, on the
            // same thread that was last decoding.
            val currentStream = rec.createStream()
            // Second stream, fed with bigger acceptWaveform batches (~1 s
            // instead of 100 ms). Sherpa's streaming decoder commits different
            // tokens depending on how much audio arrives per call — bigger
            // calls give it more context per decoder pass and sometimes catch
            // words the streaming pass misses. Feeds the NotUnderstood retry
            // path in VoiceSession.
            val parStream = rec.createStream()
            Log.d(TAG, "Streams created (main + parallel)")
            // Built here rather than in the caller so the ONNX load lands on
            // this coroutine's background thread — startListening() is called
            // from the main thread on the tap-to-talk path. A gate we can't
            // build is not fatal: the endpoint just runs unvetoed, exactly as
            // it did before this existed.
            val gate = speechGate ?: try {
                SpeechGate(context.assets).also { speechGate = it }
            } catch (t: Throwable) {
                Log.w(TAG, "VAD unavailable — endpoint veto disabled", t)
                null
            }
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
            // Why this and not RMS: sherpa-onnx's streaming decoder lags
            // audio by an unpredictable amount on-device, and audio-energy
            // detection fires too early because sherpa can be 500-1000ms
            // behind real time when the user stops speaking. inputFinished()
            // is not an alternative to this — it decides nothing about *when*
            // the utterance ends — but it does force the decoder to commit
            // what it still owes, so it is called once the endpoint fires.
            // (An earlier note here claimed otherwise. It was wrong, and it
            // cost us every truncated transcript in the capture set.)
            // Partial-text stability is the least-bad signal we have — but
            // it is only ever a proxy for "the user stopped", so [gate]
            // holds it back while silero can still hear speech. See
            // shouldEndpoint().
            var lastCleaned = ""
            var lastChangeAt = System.currentTimeMillis()
            try {
                gate?.beginUtterance()
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

                    // One clock reading per iteration, taken before the decode
                    // (which can burn tens of ms): the VAD's speech timestamps
                    // and the partial-stability window have to be measured on
                    // the same instant or comparing them is meaningless.
                    val now = System.currentTimeMillis()

                    val floatBuffer = FloatArray(merged.size) { i -> merged[i] / 32768.0f }
                    currentStream.acceptWaveform(floatBuffer, SAMPLE_RATE)
                    gate?.feed(floatBuffer, now)

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
                    if (mainBatchCount > PREROLL_SKIP_BATCHES) {
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

                    if (cleanedPartial != lastCleaned) {
                        lastCleaned = cleanedPartial
                        lastChangeAt = now
                    }
                    // Report zero stability while the partial is empty (or
                    // just changed) so it can't satisfy the stability arm.
                    // The check still has to run in those cases, because the
                    // 30s cap is the only thing that ends a session where
                    // noise keeps the VAD hot and the decoder never settles.
                    val stableForMs = if (cleanedPartial.isEmpty()) 0L else now - lastChangeAt
                    val sinceSpeech = gate?.msSinceLastSpeech(now) ?: Long.MAX_VALUE
                    if (shouldEndpoint(
                            partialStableForMs = stableForMs,
                            msSinceLastSpeech = sinceSpeech,
                            listeningForMs = now - startTime,
                        )
                    ) {
                        Log.i(
                            TAG,
                            "Custom endpoint: stable=${stableForMs}ms " +
                                "sinceSpeech=${if (sinceSpeech == Long.MAX_VALUE) "never" else "${sinceSpeech}ms"} " +
                                "listening=${now - startTime}ms cleaned='$cleanedPartial'",
                        )
                        // Finalise the MAIN stream before reading its result.
                        // Sherpa holds tokens back pending right context, and
                        // the endpoint fires precisely when the partial has
                        // gone quiet — which is exactly when those tokens are
                        // still owed. Reading getResult() without
                        // inputFinished() discards them, which is why every
                        // mangled clip in the debug capture set is a
                        // truncation and not a mishearing: "turn on the
                        // kitchen table light" arrived as "ton", and the
                        // parallel stream (which does finalise) had the
                        // missing words. Greedy transducer decoding cannot
                        // retract a committed token, so this can only ever
                        // append — the flushed text is the partial plus
                        // whatever was in flight.
                        var finalRaw = rawPartial
                        var finalMatch = partialMatch
                        try {
                            currentStream.inputFinished()
                            while (rec.isReady(currentStream)) {
                                rec.decode(currentStream)
                            }
                            val flushed = rec.getResult(currentStream).text.trim()
                            if (flushed.isNotEmpty()) {
                                finalRaw = flushed
                                finalMatch = matchWakePhrase(flushed, locale)
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Main stream finalisation failed — using last partial", t)
                        }
                        val finalCleaned = finalMatch.text
                        if (finalCleaned != cleanedPartial) {
                            Log.i(TAG, "Flush recovered: '$cleanedPartial' -> '$finalCleaned'")
                        }
                        // Flush any leftover audio into the parallel stream,
                        // then finalise it so its decoder commits everything
                        // it has. Read its result for the NotUnderstood
                        // retry path.
                        val parallelText = try {
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
                                parStream.acceptWaveform(tailFloat, SAMPLE_RATE)
                            }
                            parStream.inputFinished()
                            while (rec.isReady(parStream)) {
                                rec.decode(parStream)
                            }
                            val parRaw = rec.getResult(parStream).text.trim()
                            val parCleaned = stripWakePhrase(parRaw, locale)
                            Log.i(TAG, "Parallel stream final: raw='$parRaw' cleaned='$parCleaned'")
                            parCleaned.takeIf { it.isNotEmpty() && it != finalCleaned }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Parallel stream finalisation failed", t)
                            null
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
                            finalCleaned,
                            parallelText,
                            mergedAudio,
                            finalRaw,
                            wakeVerdict(finalMatch, locale),
                        )
                        stopRecording(owner)
                        return@launch
                    }
                }
            } finally {
                currentStream.release()
                parStream.release()
            }
        })
    }

    /**
     * Buffer until the user stops talking, then hand the whole utterance to
     * [transcribe].
     *
     * Shared by the offline-whisper and cloud paths because the difference
     * between them is only where the decode happens — the capture, the
     * RMS endpointing and the hard cap are identical, and duplicating that
     * loop once per backend is how the endpointing rules drift apart.
     *
     * [transcribe] runs off this coroutine's thread and may throw; the caller's
     * wrapper decides what the user hears. It emits the terminal state and
     * nothing else — tearing the listen down is this loop's job, because only
     * the loop holds the job identity that makes the teardown safe.
     */
    private fun startBufferedListening(
        rewindSeconds: Float,
        label: String,
        transcribe: suspend (ShortArray) -> Unit,
    ) {
        val channel = captureBus.arm(rewindSeconds) ?: run {
            Log.e(TAG, "CaptureBus already armed — refusing to start listening")
            _state.value = SttState.Error("Audio bus busy")
            return
        }

        _state.value = SttState.Listening("")
        Log.i(TAG, "STT ($label) listening started (rewindSeconds=$rewindSeconds)")

        armListenJob(scope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext.job
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
                            "$label endpoint: silence for ${sinceLastSpeech}ms after ${totalDuration}ms of utterance",
                        )
                        transcribe(merge(accumulator, totalSamples))
                        stopRecording(owner)
                        return@launch
                    }
                }

                if (totalSamples >= MAX_OFFLINE_UTTERANCE_SAMPLES) {
                    Log.i(TAG, "$label endpoint: hard cap (${totalSamples} samples)")
                    transcribe(merge(accumulator, totalSamples))
                    stopRecording(owner)
                    return@launch
                }
            }
        })
    }

    private fun merge(accumulator: List<ShortArray>, totalSamples: Int): ShortArray {
        val merged = ShortArray(totalSamples)
        var pos = 0
        for (b in accumulator) {
            System.arraycopy(b, 0, merged, pos, b.size)
            pos += b.size
        }
        return merged
    }

    private suspend fun decodeWhisperAndEmit(rec: OfflineRecognizer, merged: ShortArray) {
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

        // No parallel stream: whisper has no second decoder to disagree with.
        // The audio is still carried — not for a retry (whisper already saw the
        // whole utterance), but so the host can persist it for debug capture.
        _state.value = SttState.Done(
            text = match.text,
            parallel = null,
            audio = merged,
            raw = transcript,
            nameMatched = wakeVerdict(match, locale),
        )
    }

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
        // Same reasoning as unload(): cancel() doesn't wait, so the listen
        // coroutine may still be inside gate.feed(). Drop the reference and let
        // the GC free the VAD once that coroutine has gone with it.
        speechGate = null
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

    /**
     * Publish [job] as the active listen and start it. Created LAZY by the
     * caller so the field is set before a single line of the coroutine runs —
     * otherwise a listen that endpointed in the microseconds between `launch`
     * and the assignment would find no job to tear down and leave the
     * CaptureBus armed, which fails every subsequent listen with "Audio bus
     * busy".
     */
    private fun armListenJob(job: Job) {
        val previous = synchronized(listenLock) {
            listenJob.also { listenJob = job }
        }
        previous?.cancel()
        job.start()
    }

    /**
     * Cancel the active listen and hand the mic back. Native stream handles are
     * deliberately NOT touched here: the listen coroutine owns them and frees
     * them on its own unwind, which is what keeps two threads from freeing the
     * same one (see [startOnlineListening]).
     *
     * A listen coroutine tearing down its own turn passes itself as [owner], and
     * the teardown is skipped if it is no longer the active listen. By the time
     * a decode loop reaches its endpoint the user may already have dismissed and
     * re-armed — an unconditional teardown would then disarm the fresh session
     * on behalf of a turn nobody is waiting for. External callers pass nothing
     * and mean "whatever is listening, stop".
     */
    private fun stopRecording(owner: Job? = null) {
        val active = synchronized(listenLock) {
            val current = listenJob
            if (owner != null && current !== owner) return
            listenJob = null
            current
        }
        active?.cancel()
        captureBus.disarm()
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
     * @param audio Raw 16-bit PCM of the entire captured utterance, from
     *   whichever path produced [text]. Null only on the manual
     *   [SpeechRecognizer.stopListening] path, which has no buffer to hand
     *   over. Non-null audio is NOT on its own a licence to retry — that is
     *   what [SpeechRecognizer.isStreaming] is for; feeding whisper's audio
     *   back into [SpeechRecognizer.transcribeOffline] would re-decode an
     *   utterance the model has already seen in full.
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
