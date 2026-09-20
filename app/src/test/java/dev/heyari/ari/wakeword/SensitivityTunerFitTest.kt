package dev.heyari.ari.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tuner's arithmetic, without a microphone.
 *
 * [SensitivityTuner.fit] is the whole feature: everything else in that class
 * records audio and searches for thresholds, and this is the part that turns
 * the numbers into the setting a household lives with.
 */
class SensitivityTunerFitTest {

    private val model = WakeWordRegistry.byId("hey_ari")

    @Test
    fun `nothing spoken yet is null, not a ladder built from no evidence`() {
        assertNull(SensitivityTuner.fit(emptyList(), model))
        assertNull(SensitivityTuner.fit(listOf(session(phrases = emptyList())), model))
    }

    @Test
    fun `the middle rung clears the loudest decoy`() {
        val fit = SensitivityTuner.fit(
            listOf(session(phrases = listOf(0.99f, 0.98f), decoys = listOf(0.70f, 0.40f))),
            model,
        )!!

        assertEquals(0.80f, fit.ladder.medium, 0.0001f)
        assertEquals(0.70f, fit.worstDecoy, 0.0001f)
    }

    @Test
    fun `the household's worst decoy sets it, not each session's own`() {
        // One setting serves the house, so the person whose voice defeats it
        // is the person who decides where it sits. Averaging the sessions would
        // let a quiet one hide a loud one.
        val fit = SensitivityTuner.fit(
            listOf(
                session(phrases = listOf(0.99f), decoys = listOf(0.30f)),
                session(phrases = listOf(0.99f), decoys = listOf(0.82f)),
            ),
            model,
        )!!

        assertEquals(0.92f, fit.ladder.medium, 0.0001f)
    }

    @Test
    fun `a silent room cannot loosen the built-in ladder`() {
        // Decoys that never fired at all. Solved naively this asks for a
        // cutoff of 0.10, which is below anything a kitchen does.
        val fit = SensitivityTuner.fit(
            listOf(session(phrases = listOf(0.99f, 0.99f), decoys = listOf(0f, 0f))),
            model,
        )!!

        assertEquals(model.medium.probabilityCutoff, fit.ladder.medium, 0.0001f)
    }

    @Test
    fun `phrases that still land at the new setting are counted`() {
        val fit = SensitivityTuner.fit(
            listOf(session(phrases = listOf(0.99f, 0.95f, 0.10f), decoys = listOf(0.60f))),
            model,
        )!!

        assertEquals(2, fit.phrasesHeard)
        assertEquals(3, fit.phrasesTotal)
    }

    @Test
    fun `decoys that score like the wake phrase are reported as overlapping`() {
        // The result the screen must not dress up: ordinary speech reaching the
        // same scores as the phrase means no cutoff separates them.
        val fit = SensitivityTuner.fit(
            listOf(session(phrases = listOf(0.80f, 0.75f, 0.70f), decoys = listOf(0.88f))),
            model,
        )!!

        assertTrue(fit.overlapping)
        assertEquals(0, fit.phrasesHeard)
    }

    @Test
    fun `a clean separation is not reported as overlapping`() {
        val fit = SensitivityTuner.fit(
            listOf(session(phrases = listOf(0.99f, 0.98f, 0.97f, 0.99f), decoys = listOf(0.30f))),
            model,
        )!!

        assertFalse(fit.overlapping)
        assertEquals(4, fit.phrasesHeard)
    }

    @Test
    fun `a session alternates the phrase with a decoy and never repeats one`() {
        val takes = SensitivityTuner.takeList()

        assertEquals(SensitivityTuner.TAKES, takes.size)
        assertEquals(
            takes.indices.map { if (it % 2 == 0) TakeKind.PHRASE else TakeKind.DECOY },
            takes.map { it.kind },
        )
        val decoys = takes.filter { it.kind == TakeKind.DECOY }.map { it.promptRes }
        assertEquals("a repeated decoy measures one word twice", decoys.size, decoys.distinct().size)
    }

    private fun session(
        phrases: List<Float>,
        decoys: List<Float> = listOf(0f),
    ) = TunedSession(id = 1, phraseScores = phrases, decoyScores = decoys)
}
