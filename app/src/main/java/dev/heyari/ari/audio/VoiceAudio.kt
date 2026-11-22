package dev.heyari.ari.audio

/**
 * The microphone's audio into the engine's voice pipeline
 * (`ari_voice_push_audio` in `libari_ffi.so`).
 *
 * JNI rather than UniFFI on purpose: the capture thread calls this every 30 ms
 * for as long as Ari listens, and UniFFI's route copies the read into a
 * buffer through JNA on every call. Does nothing until the pipeline exists.
 */
object VoiceAudio {
    init {
        // The same library UniFFI loads through JNA; loading it here as well
        // registers its JNI entry point with this class.
        System.loadLibrary("ari_ffi")
    }

    /** The first [count] samples of [samples], 16 kHz mono, as the mic delivered them. */
    fun push(samples: ShortArray, count: Int) = nativePush(samples, count)

    @JvmStatic
    private external fun nativePush(samples: ShortArray, count: Int)
}

/**
 * [samples] from [from] up to [to] as 16-bit little-endian bytes: how audio
 * crosses UniFFI, where a ShortArray would arrive as a boxed list.
 */
fun pcmBytes(samples: ShortArray, from: Int = 0, to: Int = samples.size): ByteArray {
    val bytes = ByteArray((to - from) * 2)
    for (i in from until to) {
        val s = samples[i].toInt()
        bytes[(i - from) * 2] = s.toByte()
        bytes[(i - from) * 2 + 1] = (s shr 8).toByte()
    }
    return bytes
}
