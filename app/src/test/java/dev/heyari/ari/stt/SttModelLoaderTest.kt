package dev.heyari.ari.stt

import dev.heyari.ari.stt.SttModelLoader.Readiness
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth table for [SttModelLoader.decide] — the pure readiness decision
 * behind the lazy-load wait. A loaded model is READY; a selected+downloaded
 * but not-yet-warm model is COLD (show "one moment", load, then listen);
 * anything else is NOT_INSTALLED (the existing "no speech model" error).
 */
class SttModelLoaderTest {

    @Test
    fun loaded_isReady() {
        assertEquals(Readiness.READY, SttModelLoader.decide(isModelLoaded = true, hasDownloadedModel = false))
    }

    @Test
    fun loadedAlwaysWins_evenWithDownloadedModel() {
        assertEquals(Readiness.READY, SttModelLoader.decide(isModelLoaded = true, hasDownloadedModel = true))
    }

    @Test
    fun notLoadedButDownloaded_isCold() {
        assertEquals(Readiness.COLD, SttModelLoader.decide(isModelLoaded = false, hasDownloadedModel = true))
    }

    @Test
    fun notLoadedAndNotDownloaded_isNotInstalled() {
        assertEquals(Readiness.NOT_INSTALLED, SttModelLoader.decide(isModelLoaded = false, hasDownloadedModel = false))
    }
}
