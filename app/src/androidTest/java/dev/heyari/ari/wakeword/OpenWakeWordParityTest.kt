package dev.heyari.ari.wakeword

import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The phone's openWakeWord engine against the laptop scorer the exam used
 * (ari-tools exam_oww.py), on the same audio. If they disagree, the exam says
 * nothing about the phone.
 *
 * `oww_parity.wav` is a Common Voice English clip (CC0) and three synthetic
 * "Hey Ari"s from piper-sample-generator, behind the 2 s lead exam_oww puts in
 * front of every clip; `oww_parity_scores.txt` is exam_oww's score for each
 * 80 ms frame after that lead, from frame 25. Both are made by
 * `ari-tools/wakeword/corpus/tater/scripts/oww_parity_fixture.py`.
 */
@RunWith(AndroidJUnit4::class)
class OpenWakeWordParityTest {

    private val appAssets = InstrumentationRegistry.getInstrumentation().targetContext.assets
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    private val audio = readWav(testAssets, "oww_parity.wav")
    private val expected = testAssets.open("oww_parity_scores.txt").bufferedReader()
        .readLines().map { it.toFloat() }

    private val model = WakeWordRegistry.byId("hey_ari_oww")

    private fun engine(cutoff: Float, resetOnDetection: Boolean) = OpenWakeWord(
        melspectrogram = load(OWW_MELSPECTROGRAM_ASSET),
        embedding = load(OWW_EMBEDDING_ASSET),
        classifier = load(model.assetFilename),
        probabilityCutoff = cutoff,
        resetOnDetection = resetOnDetection,
    )

    @Test
    fun scoresMatchTheLaptopScorer() {
        // The fixture has to span the cutoffs, or agreeing on it proves little.
        assertTrue(expected.max() > 0.95f && expected.min() < 0.001f)
        assertTrue(expected.any { it in 0.3f..0.7f })

        val scores = engine(cutoff = 1f, resetOnDetection = true).use { engine ->
            reads().flatMap { engine.scores(it).asIterable() }
        }

        assertEquals(audio.size / FRAME, scores.size)
        // Nothing until the classifier's 16 embeddings are all audio.
        assertEquals(List(WARMUP_FRAMES) { 0f }, scores.take(WARMUP_FRAMES))
        val compared = scores.drop(FIRST_EXPECTED_FRAME)
        assertEquals(expected.size, compared.size)
        val worst = compared.indices.maxBy { abs(compared[it] - expected[it]) }
        assertTrue(
            "frame ${worst + FIRST_EXPECTED_FRAME}: phone ${compared[worst]}, laptop ${expected[worst]}",
            abs(compared[worst] - expected[worst]) <= TOLERANCE,
        )
    }

    @Test
    fun withoutResetEveryFrameOverTheCutoffIsReported() {
        val cutoff = model.medium.probabilityCutoff
        val detections = engine(cutoff, resetOnDetection = false).use { engine ->
            reads().count { engine.processAudio(it) }
        }

        // Each read is shorter than a frame, so completes at most one.
        assertEquals(expected.count { it > cutoff }, detections)
    }

    @Test
    fun resetOnDetectionGoesDeafForTheWarmUp() {
        val cutoff = model.medium.probabilityCutoff
        // Whole frames, so the samples a detection discards are none and the
        // scores after each reset line up with the laptop's.
        val detections = engine(cutoff, resetOnDetection = true).use { engine ->
            audio.asList().chunked(FRAME).count { engine.processAudio(it.toShortArray()) }
        }

        var expectedDetections = 0
        var frame = 0
        while (frame < expected.size) {
            if (expected[frame] > cutoff) {
                expectedDetections++
                frame += WARMUP_FRAMES + 1
            } else {
                frame++
            }
        }
        assertEquals(expectedDetections, detections)
        assertTrue(expectedDetections in 2 until expected.count { it > cutoff })
    }

    /** The fixture in the capture loop's reads (WakeWordService.CHUNK_SIZE). */
    private fun reads(): List<ShortArray> = audio.asList().chunked(480) { it.toShortArray() }

    private fun load(asset: String): ByteBuffer {
        val bytes = appAssets.open(asset).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }
    }

    private companion object {
        const val FRAME = 1280
        const val WARMUP_FRAMES = 24
        const val FIRST_EXPECTED_FRAME = 25

        /**
         * Set before the first run. Float rounding across ONNX Runtime versions
         * and CPUs moves a score by far less; a port that gets the pipeline
         * wrong moves it by far more.
         */
        const val TOLERANCE = 1e-3f

        /** The samples of a 16-bit mono WAV's data chunk. */
        fun readWav(assets: AssetManager, name: String): ShortArray {
            val bytes = ByteBuffer.wrap(assets.open(name).use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)
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
    }
}
