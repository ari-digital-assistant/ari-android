package dev.heyari.ari.wakeword

import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer

class MicroWakeWord(
    modelBuffer: ByteBuffer,
    featureStepSizeMs: Int,
    probabilityCutoff: Float,
    slidingWindowSize: Int,
) : Closeable {

    private var nativeHandle: Long = 0

    init {
        require(featureStepSizeMs > 0) { "featureStepSizeMs must be positive" }
        require(slidingWindowSize > 0) { "slidingWindowSize must be positive" }
        require(probabilityCutoff in 0f..1f) { "probabilityCutoff must be in [0.0, 1.0]" }
        require(modelBuffer.isDirect) { "modelBuffer must be a direct ByteBuffer" }
        ensureLibraryLoaded()
        nativeHandle = nativeCreate(modelBuffer, DEFAULT_SAMPLE_RATE, featureStepSizeMs, probabilityCutoff, slidingWindowSize)
        if (nativeHandle == 0L) {
            throw IllegalStateException("Failed to create native MicroWakeWord engine")
        }
        Log.d(TAG, "MicroWakeWord engine created")
    }

    fun processAudio(samples: ShortArray): Boolean {
        check(nativeHandle != 0L) { "MicroWakeWord has been closed" }
        return nativeProcessAudio(nativeHandle, samples)
    }

    fun reset() {
        check(nativeHandle != 0L) { "MicroWakeWord has been closed" }
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
        const val TAG = "MicroWakeWord"
        const val DEFAULT_SAMPLE_RATE = 16000

        private val libraryLoaded: Unit by lazy {
            System.loadLibrary("microwakeword")
            Log.d(TAG, "Loaded microwakeword native library")
            Unit
        }

        fun ensureLibraryLoaded() {
            libraryLoaded
        }

        @JvmStatic
        external fun nativeCreate(
            modelBuffer: ByteBuffer,
            sampleRate: Int,
            featureStepSizeMs: Int,
            probabilityCutoff: Float,
            slidingWindowSize: Int,
        ): Long

        @JvmStatic
        external fun nativeProcessAudio(handle: Long, samples: ShortArray): Boolean

        @JvmStatic
        external fun nativeReset(handle: Long)

        @JvmStatic
        external fun nativeDestroy(handle: Long)
    }
}
