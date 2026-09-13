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
 * ## Then two voices the model had never heard arrived
 *
 * Four speakers, fifteen or twenty marked takes each, same kitchen, beside the
 * phone. The middle column is what the app shipped as its default:
 *
 * Scored across each continuous recording rather than on clips cut out of it,
 * which is the only version that matches what the phone does — see
 * `eval_model.score_segment`. Denominators are takes the speaker marked herself.
 *
 * | speaker, position | 0.985 / 10 | 0.999 / 5 | 0.95 / 5 | 0.9 / 5 |
 * |---|---|---|---|---|
 * | B, beside phone  | 7/16 | 6/16 | 10/16 | 11/16 |
 * | B, few steps     | 8/15 | 7/15 | 10/15 | 10/15 |
 * | B, across room   | 3/15 | 2/15 |  4/15 |  6/15 |
 * | C, beside phone  | 8/15 | 4/15 |  8/15 |  8/15 |
 * | D, beside phone  | 8/15 | 6/15 | 10/15 | 10/15 |
 *
 * Nobody is served well. Every speaker except A is missed a third of the time
 * standing next to the phone, and speaker B more than half the time across a
 * quiet room. What the ladder can do is stop making it worse: the shipped
 * default was the weakest column for four of the five positions measured.
 *
 * So the whole ladder moved rather than just its loose end. LOW is the old
 * default, kept for anyone who was happy with it; MEDIUM is 0.95, which serves
 * every speaker measured; HIGH is 0.9 for a household that still gets missed.
 * The ten-minute kitchen recording detects nothing at any of the three.
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
        probabilityCutoff = 0.95f,
        slidingWindowSize = 5,
        displayNameRes = R.string.wakeword_sensitivity_medium_label,
        descriptionRes = R.string.wakeword_sensitivity_medium_description,
    ),
    LOW(
        probabilityCutoff = 0.985f,
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
