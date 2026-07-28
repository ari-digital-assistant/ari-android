package dev.heyari.ari.stt

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.audio.AudioClipStore
import dev.heyari.ari.audio.ClipStats
import dev.heyari.ari.audio.clipStem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** Which kind of turn produced the utterance. Recorded in the sidecar. */
enum class UtteranceTurn(val slug: String) {
    /** The first turn of a session — a wake word, or a tap on the mic. */
    OPENING("opening"),

    /** An answer to a question a skill asked, on a mic re-armed without a wake word. */
    REPLY("reply"),

    /** A turn inside continuous "let's talk" mode. */
    TALK("talk"),

    /** Composer dictation: transcribed into the text field, never dispatched. */
    DICTATION("dictation"),
}

/** What became of the transcript. Also the last segment of the filename. */
enum class UtteranceOutcome(val slug: String) {
    /** The engine acted on the first transcript. */
    ANSWERED("answered"),

    /** The engine only acted after a parallel or offline retry corrected the transcript. */
    RESCUED("rescued"),

    /** Every transcript this turn produced left the engine none the wiser. */
    NOT_UNDERSTOOD("not-understood"),

    /** The words matched a button on the card on screen, so no engine dispatch happened. */
    CARD("card"),

    /** The recogniser endpointed with nothing to show for it. */
    BLANK("blank"),

    /** Dictation, which has no engine outcome to report. */
    DICTATED("dictated"),
}

/**
 * Everything known about one utterance except the audio itself. The transcript
 * fields are the three the retry ladder can produce plus the raw text before
 * wake-phrase stripping — together they show which layer heard what, which is
 * the whole point of keeping the recording.
 */
data class UtteranceCapture(
    val turn: UtteranceTurn,
    val outcome: UtteranceOutcome,
    /** How the engine replied, e.g. `action(dev.heyari.weather)`. Null off the engine path. */
    val response: String?,
    val raw: String?,
    val transcript: String,
    val parallel: String?,
    val offline: String?,
    /** The transcript Ari actually acted on. Differs from [transcript] on a rescue. */
    val used: String,
    val locale: String,
    val model: String?,
)

private const val NONE = "(none)"

/**
 * Render [capture] as the clip's `.txt` sidecar: one `key: value` line per
 * field, in a fixed order, so a directory of exports greps and diffs cleanly.
 * [zone] is a parameter rather than a `systemDefault()` call so the output is
 * deterministic under test.
 */
internal fun utteranceSidecar(
    capture: UtteranceCapture,
    timestampMs: Long,
    zone: ZoneId,
): String = buildString {
    val at = Instant.ofEpochMilli(timestampMs).atZone(zone)
    appendLine("when: ${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(at)}")
    appendLine("turn: ${capture.turn.slug}")
    appendLine("outcome: ${capture.outcome.slug}")
    appendLine("locale: ${capture.locale}")
    appendLine("model: ${capture.model ?: NONE}")
    appendLine("response: ${capture.response ?: NONE}")
    appendLine("raw: ${capture.raw ?: NONE}")
    appendLine("transcript: ${capture.transcript.ifBlank { NONE }}")
    appendLine("parallel: ${capture.parallel ?: NONE}")
    appendLine("offline: ${capture.offline ?: NONE}")
    appendLine("used: ${capture.used.ifBlank { NONE }}")
}

/**
 * Persists what the user said to Ari alongside every transcript the recogniser
 * produced for it, so mis-hearings can be diagnosed against the actual audio.
 *
 * App-private storage only, hard-bounded, and gated behind a debug setting that
 * is off by default — the caller checks the setting, this class does not. Caps
 * are larger than [dev.heyari.ari.wakeword.WakeCaptureStore]'s because this one
 * records every turn rather than the rare containment path.
 */
@Singleton
class UtteranceCaptureStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val clips = AudioClipStore(context, DIR_NAME, MAX_FILES, MAX_BYTES)

    fun save(pcm: ShortArray, capture: UtteranceCapture, timestampMs: Long) {
        clips.save(
            clipStem("utterance", timestampMs, capture.outcome.slug),
            pcm,
            utteranceSidecar(capture, timestampMs, ZoneId.systemDefault()),
        )
    }

    fun stats(): ClipStats = clips.stats()

    fun clear() = clips.clear()

    fun shareIntent(): Intent? = clips.shareIntent()

    private companion object {
        const val DIR_NAME = "utterance-captures"
        const val MAX_FILES = 100
        const val MAX_BYTES = 50L * 1024 * 1024
    }
}
