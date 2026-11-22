package dev.heyari.ari.stt

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.audio.ListenFeed
import dev.heyari.ari.audio.pcmBytes
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
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.FfiHostHeard
import uniffi.ari_ffi.FfiSttFailure
import uniffi.ari_ffi.FfiSttModelSpec
import uniffi.ari_ffi.FfiVoiceException

/**
 * Which speech-to-text runs, and Android's own speech service. The listening
 * itself is the engine's (ari-voice): its voice session starts and ends every
 * listen, through sherpa-onnx streaming for Kroko and Nemotron, a buffered
 * decode for Whisper and Parakeet, or the cloud. This loads and releases the
 * models, routes to the cloud or a system speech service, and runs that
 * service when the session asks, fed from the engine's [ListenFeed].
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

    private val _systemService = MutableStateFlow<ComponentName?>(null)

    /**
     * The recognition service [SttMode.SYSTEM_SERVICE] hands Ari's audio to, or
     * null when Ari transcribes itself or through the cloud. Set by
     * [SttModelLoader] beside [cloudMode]; a service set here wins over it.
     */
    val systemService: StateFlow<ComponentName?> = _systemService.asStateFlow()

    /**
     * Route transcription to [component], or away from any service with null.
     * Releases a loaded local recogniser on the way in, for the reason
     * [setCloudMode] does.
     */
    fun setSystemService(component: ComponentName?) {
        if (_systemService.value == component) return
        _systemService.value = component
        if (component != null) {
            pipeline.unloadStt()
            Log.i(TAG, "STT routed to ${component.flattenToShortString()}; local recogniser released")
        }
    }

    /** Ready to transcribe: a local model is warm, or cloud or a system
     *  service is selected (which need nothing loaded). */
    val isModelLoaded: Boolean
        get() = cloudMode || _systemService.value != null || pipeline.sttModelLoaded()

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
        stopServiceListening()
        pipeline.unloadStt()
    }

    fun release() {
        stopServiceListening()
        scope.cancel()
        Log.i(TAG, "STT recogniser released")
    }

    private var serviceJob: Job? = null
    private val serviceLock = Any()

    /**
     * Transcribe a listen with the selected system service, for the voice
     * session: fed from the engine's listen feed like every other path,
     * pre-roll and all, so the transcript keeps "Hey Ari" for the name check.
     * The service finds the end of speech itself; [limitMs], a quiet wake's,
     * ends the audio sooner so that it has to. What it hears goes back to the
     * session through [VoiceEngine.pipeline]'s `hostHeard`.
     */
    fun listenWithService(rewindSeconds: Float, limitMs: Long?) {
        val service = _systemService.value
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            pipeline.hostHeard(FfiHostHeard.Failed(FfiSttFailure.NO_MODEL))
            return
        }
        startServiceListening(service, rewindSeconds, limitMs)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun startServiceListening(component: ComponentName, rewindSeconds: Float, limitMs: Long?) {
        val channel = listenFeed.arm(rewindSeconds) ?: run {
            Log.e(TAG, "CaptureBus already armed, refusing to start listening")
            pipeline.hostHeard(FfiHostHeard.Failed(FfiSttFailure.BUSY))
            return
        }

        val locale = localeProvider.currentLocale()
        Log.i(TAG, "STT (${component.flattenToShortString()}) listening started (rewindSeconds=$rewindSeconds)")

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext.job
            // Everything handed over, for the false-trigger and debug captures
            // the other paths feed too. Written by the feed, read by callbacks
            // on the main thread.
            val heard = ArrayList<ShortArray>(200)
            var heardSamples = 0
            fun heardSoFar(): ByteArray = pcmBytes(synchronized(heard) { merge(heard, heardSamples) })
            val ended = CompletableDeferred<Unit>()
            val session = SystemSpeechSession(
                context = context,
                component = component,
                language = serviceLanguageTag(locale, Locale.getDefault()),
                onPartial = { text -> pipeline.hostHeard(FfiHostHeard.Partial(text)) },
                onFinal = { text ->
                    pipeline.hostHeard(FfiHostHeard.Final(text, heardSoFar()))
                    ended.complete(Unit)
                },
                onError = { error ->
                    pipeline.hostHeard(
                        when (serviceFailure(error)) {
                            ServiceFailure.NO_SPEECH -> FfiHostHeard.NoSpeech(heardSoFar())
                            ServiceFailure.REFUSED -> FfiHostHeard.Failed(FfiSttFailure.SERVICE_REFUSED)
                            ServiceFailure.FAILED -> FfiHostHeard.Failed(FfiSttFailure.SERVICE_FAILED)
                        },
                    )
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
            stopServiceListening(owner)
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

    /**
     * Stop the system service's listen. A listen tearing down its own turn
     * passes itself as [owner], and nothing happens if it is no longer the
     * active one.
     */
    fun stopServiceListening(owner: Job? = null) {
        val active = synchronized(serviceLock) {
            val current = serviceJob
            if (owner != null && current !== owner) return
            serviceJob = null
            current
        }
        active?.cancel()
        listenFeed.disarm()
    }

    companion object {
        private const val TAG = "SpeechRecognizer"
    }
}
