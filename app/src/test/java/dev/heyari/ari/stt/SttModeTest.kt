package dev.heyari.ari.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcription choice the user makes. Which model serves on-device is
 * the engine's, and tested there (`ari_voice::stt_models`).
 */
class SttModeTest {

    @Test fun only_on_device_is_not_cloud() {
        assertTrue(!SttMode.ON_DEVICE.isCloud)
        assertTrue(SttMode.OPENAI.isCloud)
        assertTrue(SttMode.SELF_HOSTED.isCloud)
        // Ari sends the audio nowhere; what the service does is its own affair.
        assertTrue(!SttMode.SYSTEM_SERVICE.isCloud)
    }

    @Test fun unknown_mode_slug_falls_back_to_on_device() {
        // Includes "cloud", the value this enum replaced. Falling back to
        // on-device costs a re-pick; falling back to a cloud mode would send
        // audio to an endpoint the user never chose.
        assertEquals(SttMode.ON_DEVICE, SttMode.fromSlug("cloud"))
        assertEquals(SttMode.ON_DEVICE, SttMode.fromSlug(null))
        assertEquals(SttMode.ON_DEVICE, SttMode.fromSlug("nonsense"))
    }

    @Test fun mode_slugs_round_trip() {
        for (mode in SttMode.entries) {
            assertEquals(mode, SttMode.fromSlug(mode.slug))
        }
    }
}
