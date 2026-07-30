package dev.heyari.ari.stt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointDecisionTest {
    @Test
    fun `stable partial with quiet vad fires`() {
        assertTrue(shouldEndpoint(partialStableForMs = 1500, msSinceLastSpeech = 1000, listeningForMs = 5_000))
    }

    @Test
    fun `stable partial is vetoed while vad still hears speech`() {
        // The reminder-clip failure: decoder stalled at "business" for 1.5s
        // while the user was saying "in one hour". VAD heard speech 200ms
        // ago -> must NOT endpoint.
        assertFalse(shouldEndpoint(partialStableForMs = 1500, msSinceLastSpeech = 200, listeningForMs = 5_000))
    }

    @Test
    fun `unstable partial never fires even when quiet`() {
        assertFalse(shouldEndpoint(partialStableForMs = 800, msSinceLastSpeech = 2_000, listeningForMs = 5_000))
    }

    @Test
    fun `hard cap fires regardless of vad`() {
        assertTrue(shouldEndpoint(partialStableForMs = 0, msSinceLastSpeech = 0, listeningForMs = 30_000))
    }

    @Test
    fun `boundary values fire exactly at thresholds`() {
        assertTrue(shouldEndpoint(partialStableForMs = 1500, msSinceLastSpeech = 1000, listeningForMs = 0))
        assertFalse(shouldEndpoint(partialStableForMs = 1500, msSinceLastSpeech = 999, listeningForMs = 29_999))
    }
}
