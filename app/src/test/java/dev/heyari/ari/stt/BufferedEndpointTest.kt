package dev.heyari.ari.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class BufferedEndpointTest {

    private val preroll = 32_000
    private val chunk = 480

    /**
     * Feeds a listen as the phone does: the pre-roll at 0, then a 30 ms chunk at
     * a time, [loud] and [heard] saying which live times are speech. Returns how
     * it ended and when.
     */
    private fun run(
        endpoint: BufferedEndpoint,
        loudPreroll: Boolean = false,
        heardPreroll: Boolean = false,
        loud: (Long) -> Boolean = { false },
        heard: (Long) -> Boolean = { false },
    ): Pair<ListenEnd, Long> {
        var samples = preroll
        endpoint.next(0, loudPreroll, heardPreroll, samples)?.let { return it to 0L }
        var t = 0L
        while (true) {
            t += 30
            samples += chunk
            endpoint.next(t, loud(t), heard(t), samples)?.let { return it to t }
        }
    }

    @Test
    fun `a loud bare Hey Ari ends 800 ms later, as before`() {
        assertEquals(ListenEnd.ENDPOINT to 810L, run(BufferedEndpoint(0, 3_000, 6_000), loudPreroll = true))
    }

    @Test
    fun `a call too soft to be loud is heard instead of given up on`() {
        // 2026-10-14, call 2: nothing reached the speech level, the detector heard it all.
        val (end, at) = run(BufferedEndpoint(0, 3_000, 6_000), heardPreroll = true, heard = { it in 250..1_250 })

        assertEquals(ListenEnd.ENDPOINT, end)
        assertEquals(2_040L, at)
    }

    @Test
    fun `a soft command after a loud Hey Ari is not cut off`() {
        // Call 3: only the phrase was loud, and loudness alone ended it at 0.8 s.
        val (end, at) = run(BufferedEndpoint(0, 3_000, 6_000), loudPreroll = true, heard = { it in 200..1_900 })

        assertEquals(ListenEnd.ENDPOINT, end)
        assertEquals(2_700L, at)
    }

    @Test
    fun `the detector alone holds a listen at most 4 s past the last loud sound`() {
        val telly = run(BufferedEndpoint(0), loud = { it == 1_500L }, heard = { true })

        assertEquals(ListenEnd.BOUND to 5_520L, telly)
    }

    @Test
    fun `a quiet wake transcribes what it has at its limit, however loud the room`() {
        val loudRoom = run(BufferedEndpoint(0, 3_000, 6_000), loudPreroll = true, loud = { true })

        assertEquals(ListenEnd.LIMIT to 6_000L, loudRoom)
    }

    @Test
    fun `an ordinary listen in a loud room runs to the cap`() {
        val (end, at) = run(BufferedEndpoint(0), loudPreroll = true, loud = { true })

        // The 934th live chunk takes the pre-roll past 30 s of audio.
        assertEquals(ListenEnd.CAP, end)
        assertEquals(28_020L, at)
    }

    @Test
    fun `a quiet wake that hears nothing gives up at 3 s, an ordinary listen never does`() {
        assertEquals(ListenEnd.GIVE_UP to 3_000L, run(BufferedEndpoint(0, 3_000, 6_000)))
        assertEquals(ListenEnd.CAP, run(BufferedEndpoint(0)).first)
    }
}
