package dev.heyari.ari.stt

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Thin wrapper over sherpa-onnx silero VAD, asking "has anyone been speaking
 * recently?". We call [Vad.compute] per 512-sample window and compare it with
 * [SPEECH_PROBABILITY]; the segment machinery (front/pop) is not used.
 *
 * Two uses, each with its own instance: the endpoint veto, driven by the single
 * listen coroutine in [SpeechRecognizer] through [feed], and the wake service's
 * check for speech before a wake, through [speechInTail].
 *
 * Not thread-safe: one instance belongs to one thread.
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

    /**
     * Whether [pcm] holds speech in a window ending within its last [tailSamples],
     * scored from a fresh state with the windows aligned so the last ends where
     * [pcm] does. Clears the state [feed] keeps, so the two uses never share an
     * instance.
     */
    fun speechInTail(pcm: ShortArray, tailSamples: Int): Boolean {
        vad.reset()
        var start = pcm.size % WINDOW_SAMPLES
        while (start + WINDOW_SAMPLES <= pcm.size) {
            val window = FloatArray(WINDOW_SAMPLES) { pcm[start + it] / 32768.0f }
            val probability = vad.compute(window)
            if (start + WINDOW_SAMPLES > pcm.size - tailSamples && probability >= SPEECH_PROBABILITY) {
                return true
            }
            start += WINDOW_SAMPLES
        }
        return false
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
