package dev.heyari.ari.wakeword

import android.util.Log
import java.nio.ByteBuffer

/** openwakeword 0.6.0's feature models, from its release v0.5.1. */
internal const val OWW_MELSPECTROGRAM_ASSET = "openwakeword_melspectrogram.onnx"
internal const val OWW_EMBEDDING_ASSET = "openwakeword_embedding.onnx"

/**
 * openWakeWord (OpenWakeWordEngine.cpp): a classifier over a speech embedding,
 * scored every 80 ms. It fires on one score over [probabilityCutoff], with no
 * window mean, and scores nothing for the first 1.92 s after it starts or
 * resets, until its embeddings are all audio.
 *
 * [melspectrogram] and [embedding] are the feature models every openWakeWord
 * classifier shares ([OWW_MELSPECTROGRAM_ASSET], [OWW_EMBEDDING_ASSET]);
 * [classifier] is the wake word itself. [resetOnDetection] false leaves the
 * engine alone when it reports a detection, as [MicroWakeWord]'s does; true
 * resets it.
 */
class OpenWakeWord(
    melspectrogram: ByteBuffer,
    embedding: ByteBuffer,
    classifier: ByteBuffer,
    probabilityCutoff: Float,
    resetOnDetection: Boolean = true,
) : WakeDetector {

    private var nativeHandle: Long = 0

    init {
        require(probabilityCutoff in 0f..1f) { "probabilityCutoff must be in [0.0, 1.0]" }
        require(melspectrogram.isDirect && embedding.isDirect && classifier.isDirect) {
            "model buffers must be direct ByteBuffers"
        }
        ensureLibraryLoaded()
        nativeHandle = nativeCreate(melspectrogram, embedding, classifier, probabilityCutoff, resetOnDetection)
        if (nativeHandle == 0L) {
            throw IllegalStateException("Failed to create native OpenWakeWord engine")
        }
        Log.d(TAG, "OpenWakeWord engine created")
    }

    override fun processAudio(samples: ShortArray): Boolean {
        check(nativeHandle != 0L) { "OpenWakeWord has been closed" }
        return nativeProcessAudio(nativeHandle, samples)
    }

    /**
     * Processes [samples] as [processAudio] does, and returns the score of each
     * 80 ms frame they complete instead of whether one crossed the cutoff. For
     * checking the port against openwakeword's own scores.
     */
    fun scores(samples: ShortArray): FloatArray {
        check(nativeHandle != 0L) { "OpenWakeWord has been closed" }
        return nativeScores(nativeHandle, samples)
    }

    override fun reset() {
        check(nativeHandle != 0L) { "OpenWakeWord has been closed" }
        nativeReset(nativeHandle)
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
    }

    @Suppress("deprecation")
    protected fun finalize() {
        close()
    }

    private companion object {
        const val TAG = "OpenWakeWord"

        private val libraryLoaded: Unit by lazy {
            System.loadLibrary("openwakeword")
            Log.d(TAG, "Loaded openwakeword native library")
            Unit
        }

        fun ensureLibraryLoaded() {
            libraryLoaded
        }

        @JvmStatic
        external fun nativeCreate(
            melspectrogram: ByteBuffer,
            embedding: ByteBuffer,
            classifier: ByteBuffer,
            probabilityCutoff: Float,
            resetOnDetection: Boolean,
        ): Long

        @JvmStatic
        external fun nativeProcessAudio(handle: Long, samples: ShortArray): Boolean

        @JvmStatic
        external fun nativeScores(handle: Long, samples: ShortArray): FloatArray

        @JvmStatic
        external fun nativeReset(handle: Long)

        @JvmStatic
        external fun nativeDestroy(handle: Long)
    }
}
