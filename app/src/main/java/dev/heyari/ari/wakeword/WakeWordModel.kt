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
 * ladder, because a cutoff means nothing on its own — it is a point on one
 * model's curve and says nothing about another's.
 *
 * A cutoff also means nothing without the noise it has to clear. Quote every
 * one of these against the loudest window mean a real room ever reached, and
 * pick it for the gap, not for how it scores on the recording you happen to
 * have. A threshold that clears twenty minutes of kitchen by 0.002 is fitted
 * to that kitchen's noise and will fire in the twenty-first minute.
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
     * Measured on 92 marked takes from four speakers and twenty minutes of
     * household ambient — a kitchen with four people talking and a living room
     * — none of which this model had ever heard (`ari-tools/wakeword/baseline/`).
     * Marks are the speaker's own tap, so the denominator is every take they
     * meant to make rather than every take something detected.
     *
     * Over that ambient the highest mean across a window of 10 is 0.349, so
     * each cutoff below is quoted by its gap above that floor: HIGH +0.10,
     * MEDIUM +0.20, LOW +0.30. Recall runs 72% / 69% / 69% across all four
     * speakers, and the ladder is deliberately flat — this model separates the
     * phrase from conversation well enough that tightening it buys headroom
     * almost for free.
     *
     * It replaced a ladder of 0.95 / 0.985 / 0.99 that had been here since
     * April, inherited rather than measured. That one sat 0.64 clear of the
     * floor and cost the quietest speaker in the household more than half her
     * wakes — 18 of 46 against 29 — while barely touching the loudest, who
     * went 13 of 16 to 14 of 16. A wake word that only answers the person who
     * set it up is the failure this ladder exists to prevent, and it is
     * invisible if you only ever test it yourself.
     *
     * Twenty minutes cannot price a rate of one false wake an hour. Treat the
     * floor as provisional and raise it when longer ambient says so.
     */
    private val heyAri = WakeWordModel(
        id = "hey_ari",
        displayName = "Hey Ari",
        assetFilename = "hey_ari.tflite",
        featureStepSizeMs = 10,
        high = OperatingPoint(0.45f, 10),
        medium = OperatingPoint(0.55f, 10),
        low = OperatingPoint(0.65f, 10),
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
