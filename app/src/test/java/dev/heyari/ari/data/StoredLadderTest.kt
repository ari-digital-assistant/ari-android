package dev.heyari.ari.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoredLadderTest {

    @Test
    fun `a model's own ladder wins over the old shared slot`() {
        assertEquals(
            "hey_ari|0.8|0.9|0.95|10",
            storedLadder("hey_ari", own = "hey_ari|0.8|0.9|0.95|10", shared = "hey_ari|0.83|0.93|0.95|10"),
        )
    }

    @Test
    fun `the old shared slot still counts for the model it was measured for`() {
        assertEquals(
            "hey_ari|0.83|0.93|0.95|10",
            storedLadder("hey_ari", own = null, shared = "hey_ari|0.83|0.93|0.95|10"),
        )
    }

    @Test
    fun `the old shared slot means nothing to any other model`() {
        // The phone that ran the trial: tuned for hey_ari, running the retrain.
        assertNull(storedLadder("hey_ari_r6b", own = null, shared = "hey_ari|0.83|0.93|0.95|10"))
        assertNull(storedLadder("hey_ari", own = null, shared = null))
    }
}
