package dev.heyari.ari.audio

import android.util.Log
import dev.heyari.ari.voice.VoiceEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import uniffi.ari_ffi.FfiFeedRead
import uniffi.ari_ffi.FfiListenFeed

/**
 * The engine's listen feed for the readers that still live in the app: the
 * sensitivity tuner, the wake sample recorder and the system speech service.
 *
 * The microphone's audio is kept by the engine (ari-voice's ring), which hands
 * an armed reader the last [arm] seconds as 100 ms slices, then every chunk,
 * without ever blocking the capture thread or dropping audio. No wake fires
 * while it is armed. One reader at a time, as the bus it replaces had.
 */
@Singleton
class ListenFeed @Inject constructor(
    private val voiceEngine: VoiceEngine,
) {
    @Volatile
    private var feed: FfiListenFeed? = null

    /** A reader holds the feed. */
    val armed: Boolean get() = voiceEngine.pipeline.isFeedArmed()

    /**
     * The last [rewindSeconds] of audio, then everything live, until [disarm].
     * Null while another reader holds the feed.
     */
    @Synchronized
    fun arm(rewindSeconds: Float): Channel<ShortArray>? {
        val opened = voiceEngine.pipeline.openFeed(rewindSeconds) ?: run {
            Log.w(TAG, "arm() called while already armed — ignoring")
            return null
        }
        feed = opened
        // Unlimited, as the engine's own channel is: the reader is never the
        // reason audio waits, and none is dropped.
        val channel = Channel<ShortArray>(capacity = Channel.UNLIMITED)
        Thread({
            while (true) {
                when (val read = opened.next(POLL_MS)) {
                    is FfiFeedRead.Chunk -> channel.trySend(shorts(read.pcm))
                    FfiFeedRead.Timeout -> if (channel.isClosedForSend) break
                    FfiFeedRead.Closed -> break
                }
            }
            channel.close()
            opened.close()
        }, "AriListenFeed").start()
        return channel
    }

    /** End the feed, which closes the reader's channel. Idempotent. */
    @Synchronized
    fun disarm() {
        // The reader thread frees the feed once it sees it end.
        feed?.disarm()
        feed = null
    }

    private companion object {
        const val TAG = "ListenFeed"
        const val POLL_MS = 200uL
    }
}

/** 16-bit little-endian PCM bytes, as the engine hands audio over, as samples. */
fun shorts(pcm: ByteArray): ShortArray =
    ShortArray(pcm.size / 2) { i -> ((pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)).toShort() }
