package dev.heyari.ari.wakeword

import dev.heyari.ari.stt.EarlyNameCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ShadowWakeTest {

    @Test
    fun `every early check verdict has a label, and no cloud is unlabelled`() {
        assertEquals("name", shadowLabelFor(EarlyNameCheck.PASS))
        assertEquals("no-name", shadowLabelFor(EarlyNameCheck.MISS))
        assertEquals("none", shadowLabelFor(EarlyNameCheck.NONE))
        assertEquals("unlabelled", shadowLabelFor(null))
    }

    @Test
    fun `the shadow still hears a call the live model woke to a moment before`() {
        val liveAt = 100_000L

        // The live wake has armed the transcript; the shadow fires 1 s later on the same call.
        assertFalse(shadowIgnored(liveAt + 1_000, armed = true, lastLiveAt = liveAt, lastShadowAt = 0L, debounceMs = 4_000))
        // Well into the transcript, the shadow is as deaf as the live model.
        assertTrue(shadowIgnored(liveAt + 5_000, armed = true, lastLiveAt = liveAt, lastShadowAt = 0L, debounceMs = 4_000))
        // Its own debounce still holds, armed or not.
        assertTrue(shadowIgnored(liveAt + 1_000, armed = false, lastLiveAt = liveAt, lastShadowAt = liveAt, debounceMs = 4_000))
        assertFalse(shadowIgnored(liveAt + 5_000, armed = false, lastLiveAt = liveAt, lastShadowAt = liveAt, debounceMs = 4_000))
    }

    @Test
    fun `a model's fingerprint is the start of its SHA-256 and leaves the buffer as it was`() {
        val buffer = ByteBuffer.allocateDirect(3).put(byteArrayOf(0x61, 0x62, 0x63)).apply { position(1) }

        // SHA-256("abc") begins ba7816bf.
        assertEquals("ba7816bf", modelFingerprint(buffer))
        assertEquals(1, buffer.position())
    }
}
