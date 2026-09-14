package dev.heyari.ari.wakeword

import androidx.annotation.StringRes
import dev.heyari.ari.R

/**
 * User-adjustable wake word sensitivity: HIGH fires more readily, LOW is
 * strictest. Follows the user's intuition and matches how Alexa and Google Home
 * label the same setting. Easy to get backwards — don't.
 *
 * The numbers live on [WakeWordModel], not here. A cutoff means nothing on its
 * own — 0.5 is the sweet spot for one model and fires on almost anything for
 * another — so each model carries its own three points and this enum is only
 * the name of which one to use. Holding absolute values here is what made a
 * retrained model silently run on its predecessor's ladder, where it scored
 * worse than the model it replaced.
 *
 * See `docs/superpowers/specs/2026-09-10-wake-word-retrain-design.md` §7.
 */
enum class WakeWordSensitivity(
    @StringRes val displayNameRes: Int,
    @StringRes val descriptionRes: Int,
) {
    HIGH(
        displayNameRes = R.string.wakeword_sensitivity_high_label,
        descriptionRes = R.string.wakeword_sensitivity_high_description,
    ),
    MEDIUM(
        displayNameRes = R.string.wakeword_sensitivity_medium_label,
        descriptionRes = R.string.wakeword_sensitivity_medium_description,
    ),
    LOW(
        displayNameRes = R.string.wakeword_sensitivity_low_label,
        descriptionRes = R.string.wakeword_sensitivity_low_description,
    );

    companion object {
        val DEFAULT = MEDIUM

        /** Loosest first. The order the ladder is presented and reasoned about in. */
        val loosestFirst: List<WakeWordSensitivity> = listOf(HIGH, MEDIUM, LOW)

        fun fromName(name: String?): WakeWordSensitivity =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
