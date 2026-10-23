package dev.heyari.ari.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class EarlyNameCheckTest {

    @Test
    fun `the short clip passes when the name check would`() {
        // Early transcripts from the offline test, 2026-10-17.
        assertEquals(EarlyNameCheck.PASS, earlyNameCheck("Hey Ari.", "en"))
        assertEquals(EarlyNameCheck.PASS, earlyNameCheck("Hei ari.", "en"))
        assertEquals(EarlyNameCheck.PASS, earlyNameCheck("Hey, R.", "en"))
    }

    @Test
    fun `a clip without the name misses, and changes nothing`() {
        assertEquals(EarlyNameCheck.MISS, earlyNameCheck("Eli.", "en"))
        assertEquals(EarlyNameCheck.MISS, earlyNameCheck("Hey, Addy.", "en"))
        assertEquals(EarlyNameCheck.MISS, earlyNameCheck("Hey Arya, what time?", "en"))
    }

    @Test
    fun `no verdict outside English, as for the whole transcript`() {
        assertEquals(EarlyNameCheck.NONE, earlyNameCheck("Ehi Ari.", "it"))
    }
}
