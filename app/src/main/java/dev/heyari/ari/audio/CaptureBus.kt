package dev.heyari.ari.audio

import android.util.Log
import kotlinx.coroutines.channels.Channel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single point of contact between the always-on mic producer (the wake
 * word service) and any audio consumer that wants live PCM (currently just
 * sherpa STT).
 *
 * The producer calls [write] for every chunk it reads from `AudioRecord`.
 * Every chunk goes into the ring buffer unconditionally so that consumers
 * can rewind into pre-detection audio when armed. When [armed] is true the
 * chunk is also forwarded to the live channel.
 *
 * STT consumers call [arm] when they want audio. [arm] atomically:
 *   1. Snapshots the last `rewindSamples` samples from the ring buffer
 *      (i.e. audio captured *before* arming — the user's first words after
 *      "Hey Ari").
 *   2. Creates a fresh channel.
 *   3. Pushes the snapshot as the first element.
 *   4. Flips [armed] to true.
 *
 * Subsequent producer chunks land in the same channel chronologically. When
 * the consumer is done it calls [disarm], which closes the channel and flips
 * [armed] to false.
 *
 * Battery contract: when disarmed, the producer does almost nothing extra
 * beyond what the wake word service was already doing — a single ring buffer
 * memcpy and an atomic flag check. No sherpa code runs at all because no
 * consumer is draining the channel.
 */
@Singleton
class CaptureBus @Inject constructor() {

    private val ringBuffer = AudioRingBuffer(RING_CAPACITY_SAMPLES)

    @Volatile
    private var liveChannel: Channel<ShortArray>? = null

    @Volatile
    var armed: Boolean = false
        private set

    /** Producer entrypoint. Always writes to the ring buffer; forwards live
     *  chunks to the channel iff armed. Never blocks: channel uses
     *  DROP_OLDEST so a stalled consumer cannot back-pressure the mic. */
    fun write(chunk: ShortArray, count: Int = chunk.size) {
        if (count <= 0) return
        ringBuffer.write(chunk, count)
        if (armed) {
            // Copy because the producer reuses its read buffer between calls.
            val copy = if (count == chunk.size) chunk.copyOf() else chunk.copyOf(count)
            liveChannel?.trySend(copy)
        }
    }

    /**
     * Open the gate. Snapshots the last [rewindSeconds] seconds of audio from
     * the ring buffer, returns a channel whose first element is that snapshot
     * and whose subsequent elements are live chunks.
     *
     * Safe to call from any thread. Does nothing and returns null if already
     * armed (caller should disarm first if it wants a fresh subscription).
     */
    @Synchronized
    fun arm(rewindSeconds: Float): Channel<ShortArray>? {
        if (armed) {
            Log.w(TAG, "arm() called while already armed — ignoring")
            return null
        }
        val head = ringBuffer.samplesWritten
        val rewindSamples = (SAMPLE_RATE * rewindSeconds).toLong()
        val from = (head - rewindSamples).coerceAtLeast(0L)
        val preroll = ringBuffer.snapshot(from, head)
        // UNLIMITED capacity. trySend never suspends (so the producer never
        // blocks, satisfying the "producer must not block on sherpa" rule),
        // and crucially we NEVER drop user audio if sherpa hits a slow decode
        // — we just buffer a few hundred ms of PCM in RAM until sherpa
        // catches up. Dropping audio under load was causing intermittent
        // transcription corruption ("what time is it" → "wheat" / "okay").
        val ch = Channel<ShortArray>(capacity = Channel.UNLIMITED)
        // Push the pre-roll as 100ms slices, NOT one big blob. Sherpa is a
        // streaming zipformer and gets confused if you ram 2s of audio into
        // it as a single acceptWaveform call — the encoder rhythm breaks and
        // transcription truncates ("tell me some wisdom" → "tell"). Splitting
        // into 100ms pieces makes pre-roll indistinguishable from live audio
        // from sherpa's perspective.
        var off = 0
        var slices = 0
        while (off < preroll.size) {
            val n = minOf(PREROLL_SLICE_SAMPLES, preroll.size - off)
            ch.trySend(preroll.copyOfRange(off, off + n))
            off += n
            slices++
        }
        liveChannel = ch
        armed = true
        Log.i(TAG, "armed: head=$head from=$from prerollSamples=${preroll.size} slices=$slices")
        return ch
    }

    /** Close the gate and the live channel. Idempotent. */
    @Synchronized
    fun disarm() {
        if (!armed) return
        armed = false
        liveChannel?.close()
        liveChannel = null
        Log.i(TAG, "disarmed")
    }

    companion object {
        private const val TAG = "CaptureBus"
        private const val SAMPLE_RATE = 16000
        // 2 seconds of 16 kHz mono — the rewind headroom for pre-roll slicing.
        private const val RING_CAPACITY_SAMPLES = SAMPLE_RATE * 2
        // 100 ms slices for pre-roll fan-out — same cadence sherpa expects
        // from live audio so the pre-roll feed doesn't disrupt its rhythm.
        private const val PREROLL_SLICE_SAMPLES = 1600
    }
}
