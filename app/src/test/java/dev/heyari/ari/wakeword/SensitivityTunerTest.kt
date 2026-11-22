package dev.heyari.ari.wakeword

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The prompts a tuning session asks for. The arithmetic that turns the
 * session into a ladder is the engine's (ari-voice `ladder::fit`) and is
 * tested there.
 */
class SensitivityTunerTest {

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
}
