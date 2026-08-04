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

    @Test fun every_locale_resolves_to_a_shipped_model() {
        for (locale in listOf("en", "en-GB", "it", "mt", "fr", "zh-Hans", "")) {
            val model = SttModelRegistry.onDeviceFor(locale)
            assertTrue("$locale resolved off-registry", model in SttModelRegistry.all)
        }
    }
}
