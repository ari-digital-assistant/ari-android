package dev.heyari.ari.contrib

import dev.heyari.ari.stt.UtteranceCaptureStore
import dev.heyari.ari.wakeword.WakeCaptureStore
import java.io.File

/**
 * A kind of recording the user can choose to contribute, and the capture
 * directories it is drawn from.
 *
 * The slug is the path segment under the contributor's prefix in the bucket,
 * so renaming one splits a category's corpus in two. Directory names come
 * from the stores that write them rather than being repeated here.
 *
 * There is no `CONVERSATION` entry: "share all conversation audio" is an
 * umbrella over these three, exactly as the keep-everything switch is an
 * umbrella over the three capture toggles.
 */
enum class ContributionCategory(val slug: String, val dirNames: List<String>) {
    /** Confirmed true positives — the wake fired and the turn was accepted. */
    WAKE("wake", listOf(WakeCaptureStore.ACCEPTED_DIR_NAME)),

    /**
     * Hard negatives. Both directories go under one category because the
     * difference between them (nobody spoke / the transcript carried no name
     * token) is recorded in each clip's sidecar, and a reviewer needs the
     * sidecar anyway before either can enter a training set.
     */
    FALSE_TRIGGER(
        "false-trigger",
        listOf(WakeCaptureStore.DIR_NAME, WakeCaptureStore.REJECTED_DIR_NAME),
    ),

    /** What the user said to Ari, and every transcript the recogniser produced. */
    COMMAND("command", listOf(UtteranceCaptureStore.DIR_NAME)),
}

/**
 * Clips in [dir] newer than [watermark], oldest first, paired with the sidecar
 * that shares their stem.
 *
 * Stems are fixed-width timestamp prefixes (see `clipStem`), so a string
 * comparison against the watermark is a comparison of capture times. A clip
 * whose sidecar is missing is skipped rather than sent alone: audio with no
 * record of what was heard is not worth a byte of anybody's bandwidth.
 */
internal fun pendingClips(dir: File, watermark: String?): List<ClipPair> =
    dir.listFiles { f -> f.extension == "wav" }
        .orEmpty()
        .filter { watermark == null || it.nameWithoutExtension > watermark }
        .sortedBy { it.name }
        .mapNotNull { wav ->
            val sidecar = File(dir, "${wav.nameWithoutExtension}.txt")
            if (sidecar.isFile) ClipPair(wav.nameWithoutExtension, wav, sidecar) else null
        }

/** One recording: the audio, its sidecar, and the stem they share. */
internal data class ClipPair(val stem: String, val audio: File, val sidecar: File)
