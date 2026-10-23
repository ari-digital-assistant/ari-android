package dev.heyari.ari.stt

/** How a buffered (cloud or Whisper) listen ended. The slug goes in the wake log. */
enum class ListenEnd(val slug: String) {
    /** Speech, then [BufferedEndpoint.MIN_SILENCE_AFTER_SPEECH_MS] without any. */
    ENDPOINT("endpoint"),

    /** The speech detector alone held it open [BufferedEndpoint.DETECTOR_HOLD_MS] past the last loud sound. */
    BOUND("bound"),

    /** A quiet wake's listen reached its limit. */
    LIMIT("limit"),

    /** [BufferedEndpoint.MAX_SAMPLES] of audio. */
    CAP("cap"),

    /** A quiet wake heard nothing at all in time, and nothing is transcribed. */
    GIVE_UP("give-up"),
}

/** A listen's end and how long after it started, for the wake log. */
data class ListenEnded(val end: ListenEnd, val afterMs: Long)

/**
 * When a buffered listen should stop, decided chunk by chunk.
 *
 * Speech is a chunk loud enough ([SpeechRecognizer.SPEECH_RMS_THRESHOLD] on its
 * loudest frame) or one in which the speech detector heard speech. Loudness
 * alone missed soft speakers: on 2026-10-14 it ignored two whole commands said
 * quietly, and cut a third off once its "Hey Ari" had passed, taking the softer
 * command after it for silence. The detector hears them, so either counts, the
 * pre-roll included.
 *
 * The detector also hears a television and people talking in the next room,
 * and would hold a listen open as long as they go on. So it can carry a listen
 * at most [DETECTOR_HOLD_MS] past the last loud chunk, and a quiet wake, which
 * shows nothing while it listens and leaves the wake word off meanwhile, stops
 * at its own [limitMs]. Tested offline before it was built:
 * `ari-tools/wakeword/trained/2026-10-17-bounded-listen/`.
 *
 * Times are the caller's clock. The pre-roll arrives all at once at the start.
 */
internal class BufferedEndpoint(
    private val startedAt: Long,
    /** A quiet wake's: give up untranscribed if nothing counts as speech by then. */
    private val giveUpWithoutSpeechMs: Long? = null,
    /** A quiet wake's: transcribe whatever there is by then. */
    private val limitMs: Long? = null,
) {
    private var firstSpeechAt: Long? = null
    private var lastSpeechAt = startedAt
    private var lastLoudAt: Long? = null

    /** Null to keep listening. Call once per chunk, after adding it to [totalSamples]. */
    fun next(now: Long, loud: Boolean, detectorSpeech: Boolean, totalSamples: Int): ListenEnd? {
        if (loud) lastLoudAt = now
        if (loud || detectorSpeech) {
            if (firstSpeechAt == null) firstSpeechAt = now
            lastSpeechAt = now
        }
        val first = firstSpeechAt
        return when {
            first != null && now - lastSpeechAt >= MIN_SILENCE_AFTER_SPEECH_MS &&
                now - first >= MIN_UTTERANCE_MS -> ListenEnd.ENDPOINT
            first != null && now - (lastLoudAt ?: startedAt) >= DETECTOR_HOLD_MS -> ListenEnd.BOUND
            limitMs != null && now - startedAt >= limitMs -> ListenEnd.LIMIT
            totalSamples >= MAX_SAMPLES -> ListenEnd.CAP
            giveUpWithoutSpeechMs != null && first == null &&
                now - startedAt >= giveUpWithoutSpeechMs -> ListenEnd.GIVE_UP
            else -> null
        }
    }

    companion object {
        // Once speech has been heard, this much without any ends the utterance.
        // 800 ms is roughly the window the streaming path uses for partial-text
        // stability, which keeps the response time alike on both paths.
        const val MIN_SILENCE_AFTER_SPEECH_MS = 800L

        // Don't fire the endpoint until the utterance is at least this long.
        // Stops a single noise burst followed by silence from ending the
        // session before the user has actually said anything.
        const val MIN_UTTERANCE_MS = 500L

        // The same limit the streaming path puts on its detector veto
        // (VETO_OVERRIDE_STABILITY_MS): long enough to outlast a soft speaker,
        // short enough not to hand the endpoint over to the telly.
        const val DETECTOR_HOLD_MS = VETO_OVERRIDE_STABILITY_MS

        // Whisper's training window is 30 s; longer audio gets chunked
        // internally and the first segment dominates accuracy, so we cap at the
        // same point as a safety net rather than an expected case.
        const val MAX_SAMPLES = 16_000 * 30
    }
}
