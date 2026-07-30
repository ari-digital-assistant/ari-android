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
    fun `long stability overrides the veto`() {
        // Continuous speech-like noise (a television, a nearby conversation)
        // never lets msSinceLastSpeech reach the veto window, which would
        // starve the stability arm and leave the 30s cap as the only exit.
        assertTrue(shouldEndpoint(partialStableForMs = 4000, msSinceLastSpeech = 0, listeningForMs = 5_000))
    }

    @Test
    fun `just under the override still respects the veto`() {
        assertFalse(shouldEndpoint(partialStableForMs = 3999, msSinceLastSpeech = 0, listeningForMs = 5_000))
    }

    @Test
    fun `empty partial cannot fire before the hard cap`() {
        // Callers report zero stability while the partial is empty, so
        // neither the stability arm nor the override may fire on it.
        assertFalse(shouldEndpoint(partialStableForMs = 0, msSinceLastSpeech = 0, listeningForMs = 5_000))
        assertFalse(shouldEndpoint(partialStableForMs = 0, msSinceLastSpeech = Long.MAX_VALUE, listeningForMs = 29_999))
    }

    @Test
    fun `boundary values fire exactly at thresholds`() {
        assertTrue(shouldEndpoint(partialStableForMs = 1500, msSinceLastSpeech = 1000, listeningForMs = 0))
        assertFalse(shouldEndpoint(partialStableForMs = 1500, msSinceLastSpeech = 999, listeningForMs = 29_999))
    }
}
