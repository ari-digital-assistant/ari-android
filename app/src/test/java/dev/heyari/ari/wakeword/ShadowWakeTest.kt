package dev.heyari.ari.wakeword

import dev.heyari.ari.stt.EarlyNameCheck
import org.junit.Assert.assertEquals
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
    fun `a model's fingerprint is the start of its SHA-256 and leaves the buffer as it was`() {
        val buffer = ByteBuffer.allocateDirect(3).put(byteArrayOf(0x61, 0x62, 0x63)).apply { position(1) }

        // SHA-256("abc") begins ba7816bf.
        assertEquals("ba7816bf", modelFingerprint(buffer))
        assertEquals(1, buffer.position())
    }
}
