package dev.heyari.ari.wakeword

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The two beeps of a tuning session: a low one as each prompt comes up, a
 * higher one when it is time to speak, so nobody has to watch the screen to
 * keep up.
 *
 * Synthesised, and built once per session rather than per beep, so the second
 * one sounds as the screen changes instead of a buffer allocation later. Media
 * usage for the reason [dev.heyari.ari.voice.VoiceSession]'s ready cue gives:
 * the assistant streams play too quietly for a "your turn" signal.
 */
internal class TuningCues : AutoCloseable {
    private val low = tone(LOW_HZ)
    private val high = tone(HIGH_HZ)

    fun getReady() = play(low)

    fun sayIt() = play(high)

    override fun close() {
        low?.release()
        high?.release()
    }

    /** A static track has to be stopped and rewound before it plays again. */
    private fun play(track: AudioTrack?) {
        track ?: return
        runCatching {
            track.stop()
            track.reloadStaticData()
            track.play()
        }.onFailure { Log.w(TAG, "Tuning cue failed", it) }
    }

    companion object {
        private const val TAG = "TuningCues"

        const val BEEP_MS = 150
        private const val LOW_HZ = 440.0
        private const val HIGH_HZ = 880.0
        private const val RATE = 48_000
        private const val FADE_MS = 10
        private const val GAIN = 0.5

        private fun tone(hz: Double): AudioTrack? = runCatching {
            val samples = RATE * BEEP_MS / 1000
            val fade = RATE * FADE_MS / 1000
            // Faded in and out: a sine cut off mid-cycle clicks.
            val pcm = ShortArray(samples) { i ->
                val edge = minOf(i, samples - 1 - i)
                val envelope = if (edge < fade) 0.5 - 0.5 * cos(PI * edge / fade) else 1.0
                (sin(2 * PI * hz * i / RATE) * envelope * GAIN * Short.MAX_VALUE).toInt().toShort()
            }
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples * 2)
                .build()
                .also { it.write(pcm, 0, samples) }
        }.getOrElse {
            Log.w(TAG, "Could not build a $hz Hz tuning cue", it)
            null
        }
    }
}
