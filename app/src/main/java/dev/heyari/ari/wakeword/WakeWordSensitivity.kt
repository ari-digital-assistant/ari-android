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
 * ## HIGH moved again on 2026-09-13, once there was a real negative to price it
 *
 * The earlier numbers were scored against 16 hand-picked false accepts, a set
 * that saturates below 0.99 and can therefore say nothing at all about the
 * range where the second speaker's problem is actually solved. Two measurements
 * closed that gap (`ari-tools/wakeword/baseline/`):
 *
 * - Ten minutes of four people talking round a kitchen table, nobody saying the
 *   phrase: no detection at any cutoff at or above 0.7, highest window mean in
 *   the whole recording 0.59.
 * - The 16 captured false accepts span 162 hours of ordinary use — about one
 *   every ten hours, not the per-hour figure a 62-second clip set implies.
 *
 * Measured with take counts the speaker marked herself, rather than an energy
 * split guessing at the denominator:
 *
 * | cutoff / window | speaker B near | mid | across room | speaker A |
 * |---|---|---|---|---|
 * | 0.985 / 10 (shipped for months) | 25% | 33% | 13% | 19/20 |
 * | 0.995 / 5  | 56% | 33% | 20% | 19/20 |
 * | 0.9 / 5    | 88% | 60% | 47% | 20/20 |
 *
 * So HIGH is 0.9. Of the 16 false accepts that actually happened, 14 already
 * fired at the setting shipped for months and 15 fire at 0.9 — near enough the
 * rate this household already lived with, for roughly double the recall.
 *
 * MEDIUM and LOW are untouched. HIGH is the opt-in end of the ladder: a user
 * who selects it has asked to be heard more readily, which is the trade being
 * made here. Nobody gets it without choosing it.
 *
 * Caveat worth keeping: one household, and the 16 captured accepts can only
 * show false wakes that fired at the shipped setting. A lower cutoff may
 * produce ones that were never recorded because they never fired. The kitchen
 * recording is the only evidence against that, and it is ten minutes long.
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
        probabilityCutoff = 0.9f,
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
