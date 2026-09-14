package dev.heyari.ari.wakeword

/**
 * The threshold the engine compares against, and how many recent probabilities
 * it averages first. Together they are one point on a model's recall / false
 * wake curve.
 */
data class OperatingPoint(
    val probabilityCutoff: Float,
    val slidingWindowSize: Int,
)

/**
 * A wake word model bundled in app assets.
 *
 * Each model carries its own three operating points rather than sharing one
 * ladder, because a cutoff means nothing on its own: 0.5 is the sweet spot for
 * `hey_ari` and would fire on almost anything for a model calibrated near the
 * ceiling. Running the 2026-09-14 `hey_ari` at the ladder its predecessor used
 * scores 24/76 on household recordings against 60/76 at its own — worse than
 * the model it replaced, with nothing in the app to say why.
 *
 * The numbers are measured, not guessed. See `ari-tools/wakeword/baseline/` and
 * `docs/superpowers/specs/2026-09-10-wake-word-retrain-design.md` §7.
 */
data class WakeWordModel(
    val id: String,
    val displayName: String,
    val assetFilename: String,
    val featureStepSizeMs: Int,
    val high: OperatingPoint,
    val medium: OperatingPoint,
    val low: OperatingPoint,
) {
    fun operatingPoint(sensitivity: WakeWordSensitivity): OperatingPoint = when (sensitivity) {
        WakeWordSensitivity.HIGH -> high
        WakeWordSensitivity.MEDIUM -> medium
        WakeWordSensitivity.LOW -> low
    }
}

object WakeWordRegistry {
    /**
     * Measured on 92 marked takes from four speakers and ten minutes of a
     * kitchen with four people talking, none of which the model had ever heard
     * (`ari-tools/wakeword/baseline/`). At MEDIUM it catches 60 of 76 household
     * takes with no false wake in that ten minutes; HIGH reaches 65 and costs
     * one; LOW gives up 9 takes for a little more headroom.
     */
    private val heyAri = WakeWordModel(
        id = "hey_ari",
        displayName = "Hey Ari",
        assetFilename = "hey_ari.tflite",
        featureStepSizeMs = 10,
        high = OperatingPoint(0.40f, 5),
        medium = OperatingPoint(0.50f, 5),
        low = OperatingPoint(0.60f, 5),
    )

    /**
     * Unmeasured, so deliberately left on the ladder the app shipped before
     * operating points became per-model. Neither has been retrained and neither
     * has a household recording to score against; changing their behaviour on
     * the strength of `hey_ari`'s numbers would be a guess dressed as a fix.
     */
    private fun unmeasured(id: String, displayName: String) = WakeWordModel(
        id = id,
        displayName = displayName,
        assetFilename = "$id.tflite",
        featureStepSizeMs = 10,
        high = OperatingPoint(0.90f, 5),
        medium = OperatingPoint(0.95f, 5),
        low = OperatingPoint(0.985f, 10),
    )

    val all: List<WakeWordModel> = listOf(
        heyAri,
        unmeasured("ok_ari", "OK Ari"),
        unmeasured("hey_jarvis", "Hey Jarvis"),
    )

    val default: WakeWordModel = heyAri

    fun byId(id: String?): WakeWordModel = all.firstOrNull { it.id == id } ?: default
}
