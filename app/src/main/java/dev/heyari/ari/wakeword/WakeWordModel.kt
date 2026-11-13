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

/** Which engine runs a model: [MicroWakeWord] or [OpenWakeWord]. */
enum class WakeEngine { MICRO_WAKE_WORD, OPEN_WAKE_WORD }

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
    val engine: WakeEngine = WakeEngine.MICRO_WAKE_WORD,
) {
    fun operatingPoint(sensitivity: WakeWordSensitivity): OperatingPoint = when (sensitivity) {
        WakeWordSensitivity.HIGH -> high
        WakeWordSensitivity.MEDIUM -> medium
        WakeWordSensitivity.LOW -> low
    }

    /**
     * This model running on a ladder measured on the user's own phone.
     *
     * A ladder for another model is ignored rather than applied: the numbers
     * are points on one model's curve and mean nothing on another's, which is
     * the mistake this file's header exists to document. Someone switching
     * wake word gets the built-in ladder back until they tune again.
     */
    fun withLadder(ladder: TunedLadder?): WakeWordModel =
        if (ladder == null || ladder.modelId != id) this
        else copy(
            high = OperatingPoint(ladder.high, ladder.slidingWindowSize),
            medium = OperatingPoint(ladder.medium, ladder.slidingWindowSize),
            low = OperatingPoint(ladder.low, ladder.slidingWindowSize),
        )
}

object WakeWordRegistry {
    /**
     * An openWakeWord model (`ari-tools/wakeword/models/openwakeword-2026-10-28/`,
     * `rung2_half`), trained by Keith on openwakeword.com, whose models are for
     * personal and non-commercial use unless a commercial licence is bought. Its
     * classifier sits on a speech embedding pretrained on real speech, which is
     * the likely reason it hears this household where every microWakeWord
     * retrain went deaf to some of it.
     *
     * It replaced the microWakeWord `hey_ari`, called the old model below.
     *
     * It fires on a single 80 ms score, so the window is 1. Over the twenty
     * minutes of household ambient the highest score is 0.350. MEDIUM is 0.55,
     * the cutoff its offline exam ran at: 82 of 92 marked takes, none of the 34
     * held-out household false wakes, 0.5 false fires an hour on English speech
     * against the old model's 52, none on hum. HIGH is 0.50, the loosest swept
     * cutoff that clears the ambient by the exam's minimum of 0.10, and LOW is
     * 0.65, the old model's smallest gap of +0.30. Recall barely moves across
     * them: 83 / 82 / 81 of the 92 takes (Gail 45 / 44 / 43 of 46; Joe 10 of
     * 15, Keith 16 of 16 and Maryanne 12 of 15 at every level), against the old
     * model's 64 / 62 / 58.
     *
     * The gaps, +0.15 / +0.20 / +0.30, are narrower than the old model's, and
     * +0.10 has false-fired in this house before. A week live in the house (31
     * Oct to 7 Nov) answered for +0.20: none of its false wakes was ever shown,
     * and 64 reached the transcriber, against 82 for the old model running
     * beside it.
     */
    private val heyAriOpenWakeWord = WakeWordModel(
        id = "hey_ari_oww",
        displayName = "Hey Ari",
        assetFilename = "hey_ari_oww.onnx",
        // openWakeWord's melspectrogram steps 10 ms; only microWakeWord reads this.
        featureStepSizeMs = 10,
        high = OperatingPoint(0.50f, 1),
        medium = OperatingPoint(0.55f, 1),
        low = OperatingPoint(0.65f, 1),
        engine = WakeEngine.OPEN_WAKE_WORD,
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
        heyAriOpenWakeWord,
        unmeasured("ok_ari", "OK Ari"),
        unmeasured("hey_jarvis", "Hey Jarvis"),
    )

    val default: WakeWordModel = heyAriOpenWakeWord

    fun byId(id: String?): WakeWordModel = all.firstOrNull { it.id == id } ?: default
}
