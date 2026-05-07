package dev.heyari.ari.wakeword

import androidx.annotation.StringRes
import dev.heyari.ari.R

/**
 * User-adjustable wake word sensitivity. Overrides the per-model compile-time
 * cutoff and sliding window at runtime, because the right values are
 * environment-dependent — a single hardcoded threshold cannot simultaneously
 * serve a silent studio and a noisy family kitchen.
 *
 * Semantics follow the user's intuition: HIGH = fires more readily (lower
 * cutoff, shorter confirmation window), LOW = strictest (highest cutoff,
 * longest window). This matches how Alexa / Google Home label the same
 * setting. Easy to get backwards — don't.
 *
 * `displayNameRes` and `descriptionRes` are resource IDs rather than inline
 * strings so the labels translate alongside the rest of the chrome — see
 * `res/values-{locale}/strings.xml`. Compose call sites resolve them via
 * `stringResource(option.displayNameRes)`.
 */
enum class WakeWordSensitivity(
    val probabilityCutoff: Float,
    val slidingWindowSize: Int,
    @StringRes val displayNameRes: Int,
    @StringRes val descriptionRes: Int,
) {
    HIGH(
        probabilityCutoff = 0.95f,
        slidingWindowSize = 5,
        displayNameRes = R.string.wakeword_sensitivity_high_label,
        descriptionRes = R.string.wakeword_sensitivity_high_description,
    ),
    MEDIUM(
        probabilityCutoff = 0.985f,
        slidingWindowSize = 10,
        displayNameRes = R.string.wakeword_sensitivity_medium_label,
        descriptionRes = R.string.wakeword_sensitivity_medium_description,
    ),
    LOW(
        probabilityCutoff = 0.99f,
        slidingWindowSize = 14,
        displayNameRes = R.string.wakeword_sensitivity_low_label,
        descriptionRes = R.string.wakeword_sensitivity_low_description,
    );

    companion object {
        val DEFAULT = MEDIUM

        fun fromName(name: String?): WakeWordSensitivity =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
