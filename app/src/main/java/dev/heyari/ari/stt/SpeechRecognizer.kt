package dev.heyari.ari.stt

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.R
import dev.heyari.ari.audio.ListenFeed
import dev.heyari.ari.audio.pcmBytes
import dev.heyari.ari.audio.shorts
import dev.heyari.ari.locale.AriFfiLocaleProvider
import dev.heyari.ari.voice.VoiceEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.FfiEarlyNameCheck
import uniffi.ari_ffi.FfiSttEvent
import uniffi.ari_ffi.FfiSttFailure
import uniffi.ari_ffi.FfiSttModelSpec
import uniffi.ari_ffi.FfiVoiceException
import uniffi.ari_ffi.matchWakePhrase
import uniffi.ari_ffi.streamingPartial
import uniffi.ari_ffi.wakeVerdict

/**
 * Speech-to-text, as the voice session and settings use it. The recognising
 * is the engine's (ari-voice `stt`): sherpa-onnx streaming for Kroko and
 * Nemotron, a buffered decode for Whisper and Parakeet, and the cloud. This
 * turns its events into [state], keeps the system speech service (Android's
 * own, so it stays here, fed from the engine's [ListenFeed]), and maps
 * failures to words.
 *
 * Callers (the voice session, onboarding) don't need to know which path runs:
 * every one ends in the same [SttState.Done].
 */
@Singleton
class SpeechRecognizer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val voiceEngine: VoiceEngine,
    private val listenFeed: ListenFeed,
    private val localeProvider: AriFfiLocaleProvider,
) {
    private val pipeline get() = voiceEngine.pipeline
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow<SttState>(SttState.Idle)
    val state: StateFlow<SttState> = _state.asStateFlow()

    /**
     * How the latest buffered listen ended, set before its terminal state is
     * emitted. Null while listening, and for the streaming path.
     */
    @Volatile
    var lastListenEnd: ListenEnded? = null
        private set

    init {
        voiceEngine.onSttEvent = ::onEngineEvent
    }

    private fun onEngineEvent(event: FfiSttEvent) {
        when (event) {
            is FfiSttEvent.Listening -> _state.value = SttState.Listening(event.partial, event.nameMatched)
            FfiSttEvent.Transcribing -> _state.value = SttState.Transcribing
            is FfiSttEvent.Done -> {
                lastListenEnd = event.listenEnd?.let { ListenEnded(it.end, it.afterMs) }
                _state.value = SttState.Done(
                    text = event.text,
                    parallel = event.parallel,
                    audio = event.audio?.let(::shorts),
                    raw = event.raw,
                    nameMatched = event.nameMatched,
                )
            }
            is FfiSttEvent.NoSpeech -> {
                lastListenEnd = event.listenEnd?.let { ListenEnded(it.end, it.afterMs) }
                _state.value = SttState.NoSpeech(
                    context.getString(R.string.stt_cloud_error_empty),
                    event.audio?.let(::shorts),
                )
            }
            is FfiSttEvent.Failed -> _state.value = SttState.Error(message(event.failure))
        }
    }

    /** Words for a failure, so the user is told which wall they hit. */
    private fun message(failure: FfiSttFailure): String = when (failure) {
        FfiSttFailure.NO_MODEL -> "No STT model loaded. Configure one in Settings."
        FfiSttFailure.BUSY -> "Audio bus busy"
        FfiSttFailure.CLOUD_NOT_CONFIGURED -> context.getString(R.string.stt_cloud_error_not_configured)
        FfiSttFailure.CLOUD_NETWORK -> context.getString(R.string.stt_cloud_error_network)
        FfiSttFailure.CLOUD_AUTH -> context.getString(R.string.stt_cloud_error_auth)
        FfiSttFailure.CLOUD_SERVER -> context.getString(R.string.stt_cloud_error_server)
    }

    /**
     * True when transcription is routed to the cloud instead of a local
     * recogniser. Set by [SttModelLoader] from the user's [SttMode].
     */
    @Volatile
    var cloudMode: Boolean = false
        private set

    /**
     * Route transcription to the cloud (or back to a local model). The engine
     * releases any loaded recogniser on the way in: holding ~1 GB of ONNX for
     * a path that will never call it is exactly the memory pressure that gets
     * the wake-word service killed.
     */
    fun setCloudMode(enabled: Boolean) {
        if (cloudMode == enabled) return
        cloudMode = enabled
        pipeline.setCloudMode(enabled)
    }

    /**
     * The recognition service [SttMode.SYSTEM_SERVICE] hands Ari's audio to, or
     * null when Ari transcribes itself or through the cloud. Set by
     * [SttModelLoader] beside [cloudMode]; a service set here wins over it.
     */
    @Volatile
    var systemService: ComponentName? = null
        private set

    /**
     * Route transcription to [component], or away from any service with null.
     * Releases a loaded local recogniser on the way in, for the reason
     * [setCloudMode] does.
     */
    fun setSystemService(component: ComponentName?) {
        if (systemService == component) return
        systemService = component
        if (component != null) {
            pipeline.unloadStt()
            Log.i(TAG, "STT routed to ${component.flattenToShortString()}; local recogniser released")
        }
    }

    /** Ready to transcribe: a local model is warm, or cloud or a system
     *  service is selected (which need nothing loaded). */
    val isModelLoaded: Boolean
        get() = cloudMode || systemService != null || pipeline.sttModelLoaded()

    /**
     * True when the loaded recogniser is the streaming one. Hosts need this to
     * know whether [SttState.Listening] partials are a usable liveness signal:
     * the streaming path emits one per decode, whereas the buffered paths emit
     * `Listening("")` once at arm and then nothing until [SttState.Transcribing].
     */
    val isStreaming: Boolean
        get() = !cloudMode && systemService == null && pipeline.sttStreaming()

    /** Whether [checkNameEarly] might run: on cloud transcription only. */
    val canCheckNameEarly: Boolean
        get() = cloudMode

    /**
     * Transcribe [pcm], the 2 s ending at a quiet wake, on its own, and say
     * whether it holds the wake name. Null where it cannot run.
     */
    suspend fun checkNameEarly(pcm: ShortArray): FfiEarlyNameCheck? =
        withContext(Dispatchers.IO) { pipeline.checkNameEarly(pcmBytes(pcm)) }

    val currentModelId: String?
        get() = pipeline.sttModelId()

    /**
     * Loads [model] from [modelDir], releasing any other, and warms it up.
     * Blocking; a second caller for the same model returns once the first has
     * loaded it. Whisper is loaded for the current locale, and a locale change
     * reloads it.
     *
     * @throws FfiVoiceException naming what is missing or wrong.
     */
    fun loadModel(model: SttModel, modelDir: File) {
        pipeline.loadStt(
            FfiSttModelSpec(
                id = model.id,
                modelType = model.modelType,
                dir = modelDir.absolutePath,
                encoderFile = model.encoderFile,
                decoderFile = model.decoderFile,
                joinerFile = model.joinerFile,
                tokensFile = model.tokensFile,
            ),
        )
    }

    fun unload() {
        stopRecording()
        pipeline.unloadStt()
    }

    /**
     * Begin listening on the last [rewindSeconds] of the microphone and what
     * follows. [giveUpWithoutSpeechMs] and [limitMs] are a quiet wake's: the
     * buffered paths have both; a system service has only [limitMs], as it
     * gives up on silence by itself.
     */
    fun startListening(
        rewindSeconds: Float = DEFAULT_REWIND_SECONDS,
        giveUpWithoutSpeechMs: Long? = null,
        limitMs: Long? = null,
    ) {
        if (_state.value is SttState.Listening) return
        lastListenEnd = null
        val service = systemService
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            startServiceListening(service, rewindSeconds, limitMs)
        } else {
            pipeline.startListen(rewindSeconds, giveUpWithoutSpeechMs, limitMs)
        }
    }

    private var serviceJob: Job? = null
    private val serviceLock = Any()

    /**
     * Hand the listen to [component], fed from the engine's listen feed like
     * every other path, pre-roll and all, so the transcript keeps "Hey Ari"
     * for the name check. The service finds the end of speech itself;
     * [limitMs], a quiet wake's, ends the audio sooner so that it has to.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun startServiceListening(component: ComponentName, rewindSeconds: Float, limitMs: Long?) {
        val channel = listenFeed.arm(rewindSeconds) ?: run {
            Log.e(TAG, "CaptureBus already armed, refusing to start listening")
            _state.value = SttState.Error("Audio bus busy")
            return
        }

        _state.value = SttState.Listening("")
        val locale = localeProvider.currentLocale()
        Log.i(TAG, "STT (${component.flattenToShortString()}) listening started (rewindSeconds=$rewindSeconds)")

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext.job
            // Everything handed over, for the false-trigger and debug captures
            // the other paths feed too. Written by the feed, read by callbacks
            // on the main thread.
            val heard = ArrayList<ShortArray>(200)
            var heardSamples = 0
            fun heardSoFar(): ShortArray = synchronized(heard) { merge(heard, heardSamples) }
            val ended = CompletableDeferred<Unit>()
            val session = SystemSpeechSession(
                context = context,
                component = component,
                language = serviceLanguageTag(locale, Locale.getDefault()),
                onPartial = { text ->
                    streamingPartial(matchWakePhrase(text, locale), locale)?.let {
                        _state.value = SttState.Listening(it.text, it.nameMatched)
                    }
                },
                onFinal = { text ->
                    val match = matchWakePhrase(text, locale)
                    Log.i(TAG, "Service transcript: raw='$text' cleaned='${match.text}'")
                    _state.value = SttState.Done(
                        text = match.text,
                        parallel = null,
                        audio = heardSoFar(),
                        raw = text,
                        nameMatched = wakeVerdict(match, locale),
                    )
                    ended.complete(Unit)
                },
                onError = { error ->
                    _state.value = when (serviceFailure(error)) {
                        ServiceFailure.NO_SPEECH ->
                            SttState.NoSpeech(context.getString(R.string.stt_cloud_error_empty), heardSoFar())
                        ServiceFailure.REFUSED -> SttState.Error(context.getString(R.string.stt_service_error_refused))
                        ServiceFailure.FAILED -> SttState.Error(context.getString(R.string.stt_service_error_failed))
                    }
                    ended.complete(Unit)
                },
            )
            session.start()
            val startedAt = System.currentTimeMillis()
            val feed = launch(Dispatchers.IO) {
                for (chunk in channel) {
                    synchronized(heard) {
                        heard.add(chunk)
                        heardSamples += chunk.size
                    }
                    if (!session.write(chunk)) break
                    if (limitMs != null && System.currentTimeMillis() - startedAt >= limitMs) break
                }
                session.finishAudio()
            }
            try {
                ended.await()
            } finally {
                feed.cancel()
                session.close()
            }
            stopRecording(owner)
        }
        val previous = synchronized(serviceLock) { serviceJob.also { serviceJob = job } }
        previous?.cancel()
        job.start()
    }

    private fun merge(chunks: List<ShortArray>, total: Int): ShortArray {
        val merged = ShortArray(total)
        var pos = 0
        for (c in chunks) {
            System.arraycopy(c, 0, merged, pos, c.size)
            pos += c.size
        }
        return merged
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
        Log.i(TAG, "STT recogniser released")
    }

    /**
     * Decode [audio] again, whole and finalised, in a fresh streaming stream:
     * the third-layer retry for a streaming transcript the engine did not
     * understand. Null without a streaming model, or when nothing is left
     * after the wake phrase. MUST be called from a background thread.
     */
    fun transcribeOffline(audio: ShortArray): String? = pipeline.transcribeAgain(pcmBytes(audio))

    /**
     * Stop whatever is listening. A system-service listen tearing down its own
     * turn passes itself as [owner], and nothing happens if it is no longer the
     * active one.
     */
    private fun stopRecording(owner: Job? = null) {
        val active = synchronized(serviceLock) {
            val current = serviceJob
            if (owner != null && current !== owner) return
            serviceJob = null
            current
        }
        active?.cancel()
        listenFeed.disarm()
        // The engine's own listens end themselves; this is for an outside stop.
        if (owner == null) pipeline.stopListen()
    }

    companion object {
        private const val TAG = "SpeechRecognizer"

        // Generous rewind: capture the full ring (~2 s) so device-power
        // variations in arm latency never clip the user's first word.
        private const val DEFAULT_REWIND_SECONDS = 2.0f
    }
}

/** How a buffered listen ended ([end] is a `ListenEnd` slug) and when, for the wake log. */
data class ListenEnded(val end: String, val afterMs: Long)

sealed interface SttState {
    data object Idle : SttState

    /**
     * @param partial The transcript so far, wake phrase stripped.
     * @param nameMatched Whether the partial holds the wake name, decided as
     *   [Done.nameMatched] is for the whole transcript. Only the streaming
     *   recogniser has partials to judge; the buffered paths' one empty partial
     *   holds no name.
     */
    data class Listening(val partial: String, val nameMatched: Boolean? = false) : SttState

    /**
     * Buffered paths only: between the end of speech and the transcript, so
     * the host can show it is thinking rather than still listening.
     */
    data object Transcribing : SttState

    /**
     * @param text Best transcript, wake phrase stripped.
     * @param parallel Transcript from the parallel clean-start stream, or null
     *   if it was identical to [text], empty, or another path ran.
     * @param audio Raw 16-bit PCM of the whole utterance. Null only on the
     *   manual [SpeechRecognizer.stopListening] path. Non-null audio is NOT on
     *   its own a licence to retry; that is what [SpeechRecognizer.isStreaming]
     *   is for.
     * @param raw The transcript before wake-phrase stripping, or null where
     *   only a cleaned partial survived.
     * @param nameMatched Whether [raw] contained a real wake-phrase name token.
     *   Null when no verdict could be formed, treated as "accept" downstream.
     */
    data class Done(
        val text: String,
        val parallel: String? = null,
        val audio: ShortArray? = null,
        val raw: String? = null,
        val nameMatched: Boolean? = null,
    ) : SttState

    data class Error(val message: String) : SttState

    /**
     * The utterance held no speech: a quiet wake that heard nothing, or an
     * empty cloud transcript.
     *
     * @param message What to show the user, as for [Error].
     * @param audio What was heard, so a false wake can be kept.
     */
    data class NoSpeech(val message: String, val audio: ShortArray?) : SttState
}
