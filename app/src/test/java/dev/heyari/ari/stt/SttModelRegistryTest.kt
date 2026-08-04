package dev.heyari.ari.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry's two jobs: resolve the on-device model from a locale, and
 * refuse to resolve a model this build no longer ships.
 */
class SttModelRegistryTest {

    @Test fun english_gets_kroko() {
        assertEquals(SttModelRegistry.KROKO, SttModelRegistry.onDeviceFor("en"))
    }

    @Test fun english_regional_variants_get_kroko() {
        assertEquals(SttModelRegistry.KROKO, SttModelRegistry.onDeviceFor("en-GB"))
        assertEquals(SttModelRegistry.KROKO, SttModelRegistry.onDeviceFor("en_US"))
    }

    @Test fun non_english_gets_whisper_turbo() {
        // Kroko is English-only, so every other locale must land on the
        // multilingual model or the user has no on-device option at all.
        assertEquals(SttModelRegistry.WHISPER_TURBO, SttModelRegistry.onDeviceFor("it"))
        assertEquals(SttModelRegistry.WHISPER_TURBO, SttModelRegistry.onDeviceFor("mt"))
        assertEquals(SttModelRegistry.WHISPER_TURBO, SttModelRegistry.onDeviceFor("de-CH"))
    }

    @Test fun retired_model_does_not_resolve() {
        // byId returning null is what triggers migrateRetiredModel; if this
        // ever resolves again the migration silently stops running.
        assertNull(SttModelRegistry.byId("nemotron-0.6b-int8-2026-01-14"))
        assertTrue("nemotron-0.6b-int8-2026-01-14" in SttModelRegistry.retiredIds)
    }

    @Test fun retired_ids_are_never_shipped() {
        assertTrue(SttModelRegistry.all.none { it.id in SttModelRegistry.retiredIds })
    }

    @Test fun only_on_device_is_not_cloud() {
        assertTrue(!SttMode.ON_DEVICE.isCloud)
        assertTrue(SttMode.OPENAI.isCloud)
        assertTrue(SttMode.SELF_HOSTED.isCloud)
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

    @Test fun openai_preset_does_not_use_the_legacy_model() {
        // whisper-1 is legacy per OpenAI's docs and scores worse; shipping it
        // as the "more accurate than on-device" option would be self-defeating.
        assertEquals("gpt-transcribe", CloudTranscriber.OPENAI_MODEL)
        assertEquals("https://api.openai.com/v1", CloudTranscriber.OPENAI_ENDPOINT)
    }

    @Test fun every_locale_resolves_to_a_shipped_model() {
        for (locale in listOf("en", "en-GB", "it", "mt", "fr", "zh-Hans", "")) {
            val model = SttModelRegistry.onDeviceFor(locale)
            assertTrue("$locale resolved off-registry", model in SttModelRegistry.all)
        }
    }
}
