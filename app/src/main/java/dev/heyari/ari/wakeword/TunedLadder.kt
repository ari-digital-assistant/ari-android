package dev.heyari.ari.wakeword

/**
 * A ladder measured on this phone, in this household, which stands in for the
 * one [WakeWordRegistry] ships.
 *
 * The built-in ladder is the best answer available before anybody has used the
 * app: it comes from one developer's recordings, four voices and twenty
 * minutes of one house. It cannot know what this phone's microphone does, what
 * this room sounds like, or which words the people here say that happen to
 * resemble the wake phrase. Everything here exists to let the phone find that
 * out and write it down.
 *
 * Two rules keep this from becoming the fault it was built to fix:
 *
 * A stored ladder may only ever be STRICTER than the built-in one ([atLeast]).
 * A tuning session is thirty seconds in whatever room the user is standing in;
 * the built-in ladder was measured against twenty minutes of a real kitchen.
 * Letting the short measurement loosen the long one is exactly how a ladder
 * ended up at 0.45 and false-fired within two days. Tightening on local
 * evidence is sound; loosening on it is not.
 *
 * And the rungs stay [STEP] apart and inside [MIN_CUTOFF]..[MAX_CUTOFF], so
 * the three levels a user can pick never collapse into each other. Above
 * roughly 0.998 the int8 quantisation the engine does makes every cutoff the
 * same number, and a picker with three settings that behave identically is a
 * lie told three ways.
 */
data class TunedLadder(
    val modelId: String,
    val high: Float,
    val medium: Float,
    val low: Float,
    val slidingWindowSize: Int,
) {
    fun format(): String = listOf(modelId, high, medium, low, slidingWindowSize).joinToString("|")

    companion object {
        /** Below this a cutoff is in the range ordinary conversation reaches. */
        const val MIN_CUTOFF = 0.30f

        /** Above this the levels stop being distinct once quantised to int8. */
        const val MAX_CUTOFF = 0.95f

        /** Gap between rungs. Wide enough that picking a different one does something. */
        const val STEP = 0.10f

        /**
         * The narrowest a rung may sit from its neighbour once the ladder is
         * pressed against the ceiling. About five quantisation levels, so the
         * three settings stay distinct after the engine rounds them to int8.
         */
        const val MIN_GAP = 0.02f

        /**
         * A ladder centred on [medium], spaced by [STEP] and squeezed rather
         * than shifted where it meets a limit.
         *
         * MEDIUM is the rung the tuner solves for and the rung the app defaults
         * to, so it is the one a measurement is allowed to move; HIGH and LOW
         * follow it. Near the ceiling they compress toward it instead of
         * dragging MEDIUM back down, because a ladder asked for a strict middle
         * must deliver a strict middle — quietly loosening it to keep the rungs
         * evenly spaced would answer a question nobody asked.
         */
        fun centredOn(modelId: String, medium: Float, slidingWindowSize: Int): TunedLadder {
            val centre = medium.coerceIn(MIN_CUTOFF + MIN_GAP, MAX_CUTOFF - MIN_GAP)
            return TunedLadder(
                modelId = modelId,
                high = (centre - STEP).coerceIn(MIN_CUTOFF, centre - MIN_GAP),
                medium = centre,
                low = (centre + STEP).coerceIn(centre + MIN_GAP, MAX_CUTOFF),
                slidingWindowSize = slidingWindowSize,
            )
        }

        /** Parses [format]. Returns null on anything it does not recognise. */
        fun parse(stored: String?): TunedLadder? {
            val parts = stored?.split("|") ?: return null
            if (parts.size != 5) return null
            val high = parts[1].toFloatOrNull() ?: return null
            val medium = parts[2].toFloatOrNull() ?: return null
            val low = parts[3].toFloatOrNull() ?: return null
            val window = parts[4].toIntOrNull() ?: return null
            // Order is the one invariant the rest of the app relies on without
            // checking: WakeWordSensitivity's whole meaning is that LOW is the
            // strictest. A stored ladder that breaks it is corrupt, not old.
            if (high >= medium || medium >= low) return null
            if (high < MIN_CUTOFF || low > MAX_CUTOFF || window <= 0) return null
            return TunedLadder(parts[0], high, medium, low, window)
        }
    }
}

/**
 * This ladder, or the model's built-in one wherever that is stricter.
 *
 * Applied rung by rung rather than to the ladder as a whole: a local
 * measurement that tightens HIGH but would loosen LOW is half right, and there
 * is no reason to throw away the half that helps.
 */
fun TunedLadder.atLeast(model: WakeWordModel): TunedLadder = copy(
    high = maxOf(high, model.high.probabilityCutoff),
    medium = maxOf(medium, model.medium.probabilityCutoff),
    low = maxOf(low, model.low.probabilityCutoff),
)
