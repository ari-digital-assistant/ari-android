package dev.heyari.ari.wakeword

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.heyari.ari.audio.pcmBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import uniffi.ari_ffi.FfiOperatingPoint
import uniffi.ari_ffi.FfiWakeDetector
import uniffi.ari_ffi.FfiWakeModelFiles
import uniffi.ari_ffi.sileroVadProbabilities
import uniffi.ari_ffi.wakeModelFiles
import uniffi.ari_ffi.wakeWordModelById

/**
 * The engine's microWakeWord and Silero VAD on the phone's CPU, against what
 * the code they replaced produced. The same checks run on the laptop in
 * ari-engine's `tests/native_parity.rs`; these say the ARM build agrees.
 *
 * `mww_parity_detections.txt` was recorded from ari-android's own
 * MicroWakeWordEngine.cpp before step 2 moved it. The VAD is held to sherpa-
 * onnx's `Vad.compute`, which the speech check ran on until now and which the
 * app still carries for speech-to-text.
 */
@RunWith(AndroidJUnit4::class)
class NativeParityTest {

    private val appAssets = InstrumentationRegistry.getInstrumentation().targetContext.assets
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val audio = readWav("oww_parity.wav")

    @Test
    fun microWakeWordDetectsExactlyWhereTheOldEngineDid() {
        var checked = 0
        testAssets.open("mww_parity_detections.txt").bufferedReader().readLines()
            .filterNot { it.startsWith("#") }
            .forEach { line ->
                val (head, reads) = line.split(":", limit = 2)
                val (id, cutoff, window) = head.trim().split(" ")
                val model = wakeWordModelById(id)
                val files = wakeModelFiles(model).map { name -> appAssets.open(name).use { it.readBytes() } }
                val expected = reads.trim().split(" ").filter { it.isNotEmpty() }.map { it.toInt() }
                val detected = FfiWakeDetector(
                    model = model,
                    point = FfiOperatingPoint(cutoff.toFloat(), window.toInt()),
                    resetOnDetection = false,
                    files = FfiWakeModelFiles(files[0], files.getOrNull(1), files.getOrNull(2)),
                ).use { engine ->
                    audio.asList().chunked(READ).withIndex()
                        .filter { (_, read) -> engine.processAudio(pcmBytes(read.toShortArray())) }
                        .map { it.index }
                }
                assertEquals(line, expected, detected)
                checked++
            }
        assertTrue(checked >= 6)
    }

    @Test
    fun sileroVadMatchesSherpaWindowByWindow() {
        val model = appAssets.open("silero_vad.onnx").use { it.readBytes() }
        val engine = sileroVadProbabilities(model, pcmBytes(audio))

        val vad = Vad(
            appAssets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.1f,
                    windowSize = WINDOW,
                    maxSpeechDuration = 30.0f,
                ),
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            ),
        )
        val sherpa = (0 until audio.size / WINDOW).map { w ->
            vad.compute(FloatArray(WINDOW) { audio[w * WINDOW + it] / 32768.0f })
        }
        vad.release()

        // And the laptop's plain ONNX Runtime, for the record.
        val laptop = testAssets.open("vad_parity_probabilities.txt").bufferedReader().readLines().map { it.toFloat() }

        assertEquals(sherpa.size, engine.size)
        assertEquals(laptop.size, engine.size)
        val worst = engine.indices.maxBy { abs(engine[it] - sherpa[it]) }
        assertTrue(
            "window $worst: engine ${engine[worst]}, sherpa ${sherpa[worst]}",
            abs(engine[worst] - sherpa[worst]) <= TOLERANCE,
        )
        assertTrue(engine.indices.all { abs(engine[it] - laptop[it]) <= TOLERANCE })
        // Never on opposite sides of the speech check's threshold.
        assertTrue(engine.indices.none { (engine[it] >= 0.5f) != (sherpa[it] >= 0.5f) })
    }

    private fun readWav(name: String): ShortArray {
        val bytes = ByteBuffer.wrap(testAssets.open(name).use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.limit()) {
            val id = String(ByteArray(4) { bytes.get(offset + it) }, Charsets.US_ASCII)
            val size = bytes.getInt(offset + 4)
            if (id == "data") {
                val samples = ShortArray(size / 2)
                bytes.position(offset + 8)
                bytes.asShortBuffer().get(samples)
                return samples
            }
            offset += 8 + size + (size and 1)
        }
        error("$name has no data chunk")
    }

    private companion object {
        const val READ = 480
        const val WINDOW = 512

        /** The laptop found 5e-10 between this VAD and plain ONNX Runtime. */
        const val TOLERANCE = 1e-5f
    }
}
