package dev.heyari.ari.wakeword

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * MicroWakeWordEngine.cpp before step 2 moved it. The VAD is held to the
 * laptop's ONNX Runtime scores.
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
    fun sileroVadMatchesTheLaptopWindowByWindow() {
        // Step 2 held this VAD to sherpa-onnx's Vad.compute on the phone, and it
        // agreed; sherpa's Kotlin API has since left the app. The laptop's plain
        // ONNX Runtime scores are the reference that is left.
        val model = appAssets.open("silero_vad.onnx").use { it.readBytes() }
        val engine = sileroVadProbabilities(model, pcmBytes(audio))
        val laptop = testAssets.open("vad_parity_probabilities.txt").bufferedReader().readLines().map { it.toFloat() }

        assertEquals(laptop.size, engine.size)
        val worst = engine.indices.maxBy { abs(engine[it] - laptop[it]) }
        assertTrue(
            "window $worst: phone ${engine[worst]}, laptop ${laptop[worst]}",
            abs(engine[worst] - laptop[worst]) <= TOLERANCE,
        )
        // Never on opposite sides of the speech check's threshold.
        assertTrue(engine.indices.none { (engine[it] >= 0.5f) != (laptop[it] >= 0.5f) })
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

        /** The laptop found 5e-10 between this VAD and plain ONNX Runtime. */
        const val TOLERANCE = 1e-5f
    }
}
