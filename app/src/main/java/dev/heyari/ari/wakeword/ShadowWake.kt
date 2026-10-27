package dev.heyari.ari.wakeword

import dev.heyari.ari.stt.EarlyNameCheck
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Shadow mode (fix.md stage 4): a second wake model runs beside the live one,
 * hears the same audio, goes through the same speech check, and logs what it
 * would have done. It never wakes Ari, never opens the overlay and never arms
 * the microphone for a transcript, because while Ari transcribes the live wake
 * word is off, and a shadow false wake would then cost someone a real call.
 *
 * Each detection the speech check lets through is labelled by transcribing the
 * 2 s before it in the cloud, as the early name check does. A label is what
 * the check thinks, not the truth: the recordings, when they are on, are for
 * someone to listen to and say.
 */
data class ShadowRun(
    val model: WakeWordModel,
    val sensitivity: WakeWordSensitivity,
    val point: OperatingPoint,
    val fingerprint: String,
)

/** How long before a shadow detection its label and recording cover, as the early name check. */
internal const val SHADOW_CLIP_S = 2.0f

/** The wake log's label for a shadow detection, from the early name check's verdict on its clip. */
internal fun shadowLabelFor(result: EarlyNameCheck?): String = when (result) {
    EarlyNameCheck.PASS -> "name"
    EarlyNameCheck.MISS -> "no-name"
    EarlyNameCheck.NONE -> "none"
    null -> "unlabelled"
}

/** The first 8 hex digits of [model]'s SHA-256, read without moving its position. */
internal fun modelFingerprint(model: ByteBuffer): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(model.duplicate().apply { rewind() })
    return digest.digest().take(4).joinToString("") { "%02x".format(it) }
}
