package dev.heyari.ari.wakeword

/**
 * What Ari can offer somebody whose wake word keeps firing at nothing.
 *
 * Always exactly one suggestion beside doing nothing. Handing over three
 * options and a probability curve moves the decision to the person least
 * equipped to make it — they reported a symptom, not a preference about
 * thresholds.
 */
sealed interface FalseWakeRemedy {
    /**
     * Move down the ladder the app already offers. Costs nothing to undo and
     * the user can see what changed, so it comes before touching the numbers.
     */
    data class StepDown(val to: WakeWordSensitivity) : FalseWakeRemedy

    /**
     * Already on the strictest rung, so the rung itself has to move: rebuild
     * the ladder around a cutoff [TIGHTEN_STEP] above the one in force and land
     * the user on its middle.
     */
    data class Tighten(val ladder: TunedLadder) : FalseWakeRemedy

    /**
     * Nothing useful left. The ladder is already against the ceiling, where
     * further cutoffs stop being distinguishable once quantised — saying so is
     * the honest answer, and it means the model is the problem, not the number.
     */
    data object Exhausted : FalseWakeRemedy

    companion object {
        /**
         * How much stricter a [Tighten] makes the wake word.
         *
         * Small on purpose. The obvious move — jump the whole ladder so its
         * loosest rung clears the current strictest — is roughly a 0.25 step,
         * and on the household recordings that costs the quietest speaker a
         * quarter of the wakes she has left. One nudge that might need
         * repeating beats one leap that cannot be felt until somebody has given
         * up on the thing.
         */
        const val TIGHTEN_STEP = 0.05f

        /**
         * The single remedy for somebody currently on [level].
         *
         * [model] must already carry whatever ladder is actually in force —
         * [WakeWordModel.withLadder] — because the step is measured from the
         * cutoff the user is running, not from the one the app shipped with.
         */
        fun forLevel(level: WakeWordSensitivity, model: WakeWordModel): FalseWakeRemedy {
            if (level != WakeWordSensitivity.LOW) {
                // loosestFirst is HIGH, MEDIUM, LOW, so the next one along is
                // the next one stricter. Going through the enum's own order
                // rather than a when-block means a fourth rung would not
                // silently skip itself here.
                val next = WakeWordSensitivity.loosestFirst
                    .getOrNull(WakeWordSensitivity.loosestFirst.indexOf(level) + 1)
                if (next != null) return StepDown(next)
            }
            val current = model.operatingPoint(level).probabilityCutoff
            val ladder = TunedLadder.centredOn(
                modelId = model.id,
                medium = current + TIGHTEN_STEP,
                slidingWindowSize = model.operatingPoint(level).slidingWindowSize,
            )
            return if (ladder.medium > current) Tighten(ladder) else Exhausted
        }
    }
}
