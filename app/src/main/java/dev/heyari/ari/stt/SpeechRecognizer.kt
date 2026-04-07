package dev.heyari.ari.stt

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class SpeechRecognizer {

    private var recognizer: OnlineRecognizer? = null
    private var loadedModelId: String? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
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
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )

        Log.i(TAG, "Loading sherpa-onnx model: ${model.id}")
        recognizer = OnlineRecognizer(assetManager = null, config = config)
        loadedModelId = model.id
        Log.i(TAG, "Model loaded: ${model.id}")
    }

    fun unload() {
        stopRecording()
        recognizer?.release()
        recognizer = null
        loadedModelId = null
    }

    fun startListening() {
        val rec = recognizer ?: run {
            Log.e(TAG, "startListening called but no model loaded")
            _state.value = SttState.Error("No STT model loaded. Configure one in Settings.")
            return
        }
        if (_state.value is SttState.Listening) return

        stream = rec.createStream()
        Log.d(TAG, "Stream created")

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise! state=${audioRecord?.state}")
            stopRecording()
            _state.value = SttState.Idle
            return
        }

        audioRecord?.startRecording()
        _state.value = SttState.Listening("")
        Log.i(TAG, "STT listening started")

        listenJob = scope.launch {
            val buffer = ShortArray(CHUNK_SAMPLES)
            val currentStream = stream ?: return@launch

            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read <= 0) continue

                val floatBuffer = FloatArray(read) { i -> buffer[i] / 32768.0f }
                currentStream.acceptWaveform(floatBuffer, SAMPLE_RATE)

                while (rec.isReady(currentStream)) {
                    rec.decode(currentStream)
                }

                val partial = rec.getResult(currentStream).text.trim()
                if (partial.isNotEmpty()) {
                    _state.value = SttState.Listening(partial)
                }

                if (rec.isEndpoint(currentStream)) {
                    Log.i(TAG, "Endpoint detected: '$partial'")
                    if (partial.isNotEmpty()) {
                        _state.value = SttState.Done(partial)
                        stopRecording()
                        return@launch
                    }
                    rec.reset(currentStream)
                }
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
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        stream?.release()
        stream = null
    }

    companion object {
        private const val TAG = "SpeechRecognizer"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 1600 // 100ms at 16kHz
    }
}

sealed interface SttState {
    data object Idle : SttState
    data class Listening(val partial: String) : SttState
    data class Done(val text: String) : SttState
    data class Error(val message: String) : SttState
}
