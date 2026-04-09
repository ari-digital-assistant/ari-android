package dev.heyari.ari.audio

/**
 * Single-writer, multi-reader fixed-capacity PCM ring buffer with a monotonic
 * sample counter. Used by [CaptureBus] to retain ~2 s of pre-detection audio so
 * that when the wake word fires we can rewind into the user's first words and
 * feed them into sherpa with zero handover gap.
 *
 * Threading: exactly one producer thread calls [write]; any number of threads
 * may call [snapshot] or read [samplesWritten]. All access goes through the
 * monitor lock — the producer chunks are tiny (10 ms = 160 samples) so the
 * critical section is microseconds.
 */
class AudioRingBuffer(private val capacity: Int) {

    private val buffer = ShortArray(capacity)
    private val lock = Any()

    @Volatile
    private var written: Long = 0L

    /** Total samples ever written to this buffer (monotonic, never wraps). */
    val samplesWritten: Long get() = written

    /** Append [count] samples from [src]. Overwrites the oldest data on wrap. */
    fun write(src: ShortArray, count: Int = src.size) {
        if (count <= 0) return
        synchronized(lock) {
            val start = (written % capacity).toInt()
            val firstChunk = minOf(count, capacity - start)
            System.arraycopy(src, 0, buffer, start, firstChunk)
            val remainder = count - firstChunk
            if (remainder > 0) {
                System.arraycopy(src, firstChunk, buffer, 0, remainder)
            }
            written += count
        }
    }

    /**
     * Snapshot samples in the absolute range [fromSample, toSample). Both are
     * positions in the monotonic stream (i.e. [samplesWritten] values). Clamps
     * automatically: [fromSample] is bumped forward if it's older than the
     * buffer can still hold; [toSample] is clamped to the current write head.
     * Returns an empty array if the requested range is empty after clamping.
     */
    fun snapshot(fromSample: Long, toSample: Long): ShortArray {
        synchronized(lock) {
            val head = written
            val end = toSample.coerceAtMost(head)
            val oldestRetained = (head - capacity).coerceAtLeast(0L)
            val start = fromSample.coerceAtLeast(oldestRetained)
            if (start >= end) return ShortArray(0)
            val length = (end - start).toInt()
            val out = ShortArray(length)
            val ringStart = (start % capacity).toInt()
            val firstChunk = minOf(length, capacity - ringStart)
            System.arraycopy(buffer, ringStart, out, 0, firstChunk)
            val remainder = length - firstChunk
            if (remainder > 0) {
                System.arraycopy(buffer, 0, out, firstChunk, remainder)
            }
            return out
        }
    }
}
