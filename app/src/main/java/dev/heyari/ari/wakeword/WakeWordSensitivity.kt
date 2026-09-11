package dev.heyari.ari.wakeword

import androidx.annotation.StringRes
import dev.heyari.ari.R

/**
 * User-adjustable wake word sensitivity. Overrides the per-model compile-time
 * cutoff and sliding window at runtime, because the right values are
 * environment-dependent — a single hardcoded threshold cannot simultaneously
 * serve a silent studio and a noisy family kitchen.
 *
 * Semantics follow the user's intuition: HIGH = fires more readily, LOW =
 * strictest. This matches how Alexa / Google Home label the same setting. Easy
 * to get backwards — don't.
 *
 * ## The numbers changed on 2026-09-11, and the shape of them changed with it
 *
 * Strictness now rides mostly on the WINDOW, with the cutoff pinned near the
 * ceiling — the opposite of the original arrangement, which loosened the cutoff
 * and lengthened the window together.
 *
 * The old shape was built to catch the wrong thing. A long window demands the
 * score stay high across a long stretch, which is what ambient noise does:
 * vague but persistent. A real spoken word is the reverse — convincing, and
 * brief. Asking for persistence let the television through and shut out
 * anybody who says the phrase a little quicker or quieter than the developer.
 *
 * Measured against 20 recordings of one speaker, 15 of a second speaker, and
 * 16 real captured false accepts (`ari-tools/wakeword/baseline/`):
 *
 * | setting | was | speaker A | speaker B | false accepts |
 * |---|---|---|---|---|
 * | HIGH   | 0.95 / 5  | 100% -> 95% | 100% -> 93.3% | 15/16 -> 13/16 |
 * | MEDIUM | 0.985 / 10 | 95% -> 95%  | 66.7% -> 86.7% | 14/16 -> 10/16 |
 * | LOW    | 0.99 / 14 | 90% -> 90%  | 13.3% -> 40%   | 10/16 -> 9/16  |
 *
 * Every level improves or holds on both axes. The second speaker gains 20
 * points at the default while false accepts drop by four, and LOW stops being
 * the setting that ignores her entirely.
 *
 * Caveat worth keeping: speaker B's recordings are near-field, one room, one
 * session. The direction is solid; treat the magnitudes as provisional until
 * there are across-the-room recordings to check them against.
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
        probabilityCutoff = 0.995f,
        slidingWindowSize = 5,
        displayNameRes = R.string.wakeword_sensitivity_high_label,
        descriptionRes = R.string.wakeword_sensitivity_high_description,
    ),
    MEDIUM(
        probabilityCutoff = 0.999f,
        slidingWindowSize = 5,
        displayNameRes = R.string.wakeword_sensitivity_medium_label,
        descriptionRes = R.string.wakeword_sensitivity_medium_description,
    ),
    LOW(
        probabilityCutoff = 0.999f,
        slidingWindowSize = 10,
        displayNameRes = R.string.wakeword_sensitivity_low_label,
        descriptionRes = R.string.wakeword_sensitivity_low_description,
    );

    companion object {
        val DEFAULT = MEDIUM

        fun fromName(name: String?): WakeWordSensitivity =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
