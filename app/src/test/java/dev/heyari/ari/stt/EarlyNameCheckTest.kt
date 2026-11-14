package dev.heyari.ari.stt

import dev.heyari.ari.voice.matchWakePhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `a streaming partial carries the name verdict with the text after it`() {
        assertEquals(
            SttState.Listening("what time is it", true),
            streamingPartial(matchWakePhrase("Hey Ari what time is it", "en"), "en"),
        )
        assertEquals(
            SttState.Listening("we're going out", false),
            streamingPartial(matchWakePhrase("we're going out", "en"), "en"),
        )
    }

    @Test
    fun `a partial that is only the name still shows, so a bare call can reveal`() {
        assertEquals(SttState.Listening("", true), streamingPartial(matchWakePhrase("Hey Ari", "en"), "en"))
        assertEquals(SttState.Listening("", null), streamingPartial(matchWakePhrase("Ehi Ari", "it"), "it"))
    }

    @Test
    fun `a partial with neither text nor name shows nothing`() {
        assertNull(streamingPartial(matchWakePhrase("", "en"), "en"))
        assertNull(streamingPartial(matchWakePhrase("Hey", "en"), "en"))
    }
}
