package dev.heyari.ari.stt

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Thin wrapper over sherpa-onnx silero VAD used purely as an endpoint
 * VETO: "has anyone been speaking recently?". We call [Vad.compute]
 * per 512-sample window and track the last window that scored over
 * [SPEECH_PROBABILITY]; the segment machinery (front/pop) is not used.
 *
 * Not thread-safe — owned and driven by the single listen coroutine in
 * [SpeechRecognizer].
 */
class SpeechGate(assets: AssetManager) {

    private val vad = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = SPEECH_PROBABILITY,
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.1f,
                windowSize = WINDOW_SAMPLES,
                maxSpeechDuration = 30.0f,
            ),
            sampleRate = 16000,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        ),
    )

    // Carry-over so 1600-sample batches don't drop the 3.125-window tail.
    private var pending = FloatArray(0)
    private var lastSpeechAtMs = 0L

    /** Reset per-utterance state. Call at each listen start — the gate
     *  instance is reused across utterances, the VAD model is not cheap
     *  to rebuild. */
    fun beginUtterance() {
        pending = FloatArray(0)
        lastSpeechAtMs = 0L
        vad.reset()
    }

    /** Feed one decode-loop batch; [nowMs] is the caller's clock. */
    fun feed(samples: FloatArray, nowMs: Long) {
        val buf = if (pending.isEmpty()) samples else pending + samples
        var offset = 0
        while (buf.size - offset >= WINDOW_SAMPLES) {
            val window = buf.copyOfRange(offset, offset + WINDOW_SAMPLES)
            if (vad.compute(window) >= SPEECH_PROBABILITY) {
                lastSpeechAtMs = nowMs
            }
            offset += WINDOW_SAMPLES
        }
        pending = buf.copyOfRange(offset, buf.size)
    }

    /** Long.MAX_VALUE until the first speech window — arming silence must not read as "recent speech". */
    fun msSinceLastSpeech(nowMs: Long): Long =
        if (lastSpeechAtMs == 0L) Long.MAX_VALUE else nowMs - lastSpeechAtMs

    private companion object {
        // The bundled model (silero-vad v4 re-exported by k2-fsa, 16 kHz
        // branch only) declares a fixed input shape of [1, 512] — feeding
        // any other window size fails inside onnxruntime.
        const val WINDOW_SAMPLES = 512
        const val SPEECH_PROBABILITY = 0.5f
    }
}
