package dev.heyari.ari.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Locks the on-the-wire and on-disk names of the per-locale router
 * artifacts. These strings have to match exactly what CI publishes to the
 * `functiongemma-<locale>-latest` releases — a typo here is a silent
 * "no router" for that locale.
 */
class RouterModelTest {

    @Test
    fun fileNameCarriesLocale() {
        assertEquals("ari-functiongemma-en-q4_k_m.gguf", RouterModel.fileName("en"))
        assertEquals("ari-functiongemma-it-q4_k_m.gguf", RouterModel.fileName("it"))
    }

    @Test
    fun manifestUrlTargetsPerLocaleRelease() {
        assertEquals(
            "https://github.com/ari-digital-assistant/ari-tools/releases/download/" +
                "functiongemma-en-latest/manifest.json",
            RouterModel.manifestUrl("en"),
        )
        assertEquals(
            "https://github.com/ari-digital-assistant/ari-tools/releases/download/" +
                "functiongemma-it-latest/manifest.json",
            RouterModel.manifestUrl("it"),
        )
    }

    @Test
    fun englishIsSuffixedLikeEveryOtherLocale() {
        // No unsuffixed artifact survives — that's what makes the legacy
        // file a migration target rather than the en model already in place.
        assertNotEquals(RouterModel.LEGACY_FILENAME, RouterModel.fileName(RouterModel.LEGACY_LOCALE))
        assertEquals("ari-functiongemma-q4_k_m.gguf", RouterModel.LEGACY_FILENAME)
    }
}
