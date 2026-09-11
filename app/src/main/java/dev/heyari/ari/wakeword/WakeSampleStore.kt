package dev.heyari.ari.wakeword

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.BuildConfig
import dev.heyari.ari.R
import dev.heyari.ari.audio.AudioClipStore
import dev.heyari.ari.audio.ClipStats
import dev.heyari.ari.audio.clipStem
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** How far the speaker stood from the device. Recorded in the sidecar. */
enum class SampleDistance(val slug: String, @StringRes val labelRes: Int) {
    NEAR("near", R.string.wake_samples_distance_near),
    MID("mid", R.string.wake_samples_distance_mid),
    ACROSS_ROOM("across-room", R.string.wake_samples_distance_across),
}

/** What else was making noise. Recorded in the sidecar. */
enum class SampleBackground(val slug: String, @StringRes val labelRes: Int) {
    QUIET("quiet", R.string.wake_samples_background_quiet),
    TELEVISION("tv", R.string.wake_samples_background_tv),
    KITCHEN("kitchen", R.string.wake_samples_background_kitchen),
    CONVERSATION("conversation", R.string.wake_samples_background_conversation),
    MUSIC("music", R.string.wake_samples_background_music),
}

/**
 * The conditions one continuous recording was made under. A segment is a
 * position, not an utterance: the speaker says the phrase several times without
 * touching the device, and the recording is split into takes off-device.
 */
data class WakeSampleSegment(
    val setName: String,
    val phrase: String,
    val room: String,
    val distance: SampleDistance,
    val background: SampleBackground,
)

/**
 * Filename-safe rendering of free text. Unicode-aware rather than ASCII-only,
 * so a non-English room or speaker name survives as itself instead of
 * collapsing to a row of dashes.
 */
internal fun sampleSlug(value: String): String =
    value.trim()
        .lowercase(Locale.ROOT)
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .take(SLUG_MAX_CHARS)
        .ifEmpty { "unnamed" }

private const val SLUG_MAX_CHARS = 24

/**
 * Render [segment] as the clip's `.txt` sidecar: one `key: value` line per
 * field, matching the shape the other capture stores use so a directory of
 * exports greps and diffs cleanly. [zone] is a parameter rather than a
 * `systemDefault()` call so the output is deterministic under test.
 */
internal fun wakeSampleSidecar(
    segment: WakeSampleSegment,
    timestampMs: Long,
    durationMs: Long,
    zone: ZoneId,
): String = buildString {
    val at = Instant.ofEpochMilli(timestampMs).atZone(zone)
    appendLine("when: ${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(at)}")
    appendLine("set: ${segment.setName}")
    appendLine("phrase: ${segment.phrase}")
    appendLine("room: ${segment.room}")
    appendLine("distance: ${segment.distance.slug}")
    appendLine("background: ${segment.background.slug}")
    appendLine("durationMs: $durationMs")
    appendLine("app: ${BuildConfig.VERSION_NAME}")
}

/**
 * Deliberately recorded wake-phrase audio, for the held-out evaluation set a
 * retrain is measured against. See
 * `docs/superpowers/specs/2026-09-10-wake-word-retrain-design.md` §4.
 *
 * Unlike the other capture stores this is not a rolling diagnostic buffer: it
 * holds a recording session with a real person in it, which cannot be
 * reconstructed after the fact. The caps are therefore set far above any
 * plausible session rather than at a diagnostic's working size — eviction here
 * would silently destroy the one thing the retrain cannot proceed without.
 *
 * App-private storage, exported through the share sheet like every other
 * capture directory.
 */
@Singleton
class WakeSampleStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val clips = AudioClipStore(context, DIR_NAME, MAX_FILES, MAX_BYTES)

    fun save(pcm: ShortArray, segment: WakeSampleSegment, timestampMs: Long, durationMs: Long) {
        clips.save(
            clipStem("sample", timestampMs, "${sampleSlug(segment.setName)}-${sampleSlug(segment.room)}"),
            pcm,
            wakeSampleSidecar(segment, timestampMs, durationMs, ZoneId.systemDefault()),
        )
    }

    fun stats(): ClipStats = clips.stats()

    fun clear() = clips.clear()

    fun shareIntent(): Intent? = clips.shareIntent()

    fun files(): List<File> = clips.files()

    private companion object {
        const val DIR_NAME = "wake-samples"
        const val MAX_FILES = 500
        const val MAX_BYTES = 500L * 1024 * 1024
    }
}
