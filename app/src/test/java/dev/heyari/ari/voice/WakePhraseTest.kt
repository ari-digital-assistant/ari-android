package dev.heyari.ari.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {

    @Test
    fun `wake phrase with command reports a name match and strips it`() {
        val match = matchWakePhrase("hey ari whats the weather")
        assertTrue(match.nameMatched)
        assertEquals("whats the weather", match.text)
    }

    @Test
    fun `opener with no name does not report a name match`() {
        val match = matchWakePhrase("okay so whats the weather")
        assertFalse(match.nameMatched)
        assertEquals("so whats the weather", match.text)
    }

    @Test
    fun `mishear from the name list still reports a name match`() {
        val match = matchWakePhrase("harry can you set a timer")
        assertTrue(match.nameMatched)
        assertEquals("can you set a timer", match.text)
    }

    @Test
    fun `ara mishear is accepted as a name token`() {
        // Real capture 2026-07-29: user said "Hey Ari", sherpa wrote "ARA".
        val match = matchWakePhrase("ARA reminds me about business in one hour", "en")
        assertEquals("reminds me about business in one hour", match.text)
        assertTrue(match.nameMatched)
    }

    @Test
    fun `fused wake and verb still fails - documented gap`() {
        // "Hey Ari, remind" -> "Rind": no separable name token exists.
        // If this starts passing, a list entry got too greedy — investigate.
        val match = matchWakePhrase("Rind me about business in one hour", "en")
        assertFalse(match.nameMatched)
    }

    @Test
    fun `the two false wakes Ari answered are no longer addressed to it`() {
        // Real captures, 2026-09: a lecture and a TV scene, matched on "re".
        assertFalse(matchWakePhrase("So anyway, we're first sorting by country and then by city.").nameMatched)
        assertFalse(matchWakePhrase("Ah, I guess time doesn't mean much when you're dead.").nameMatched)
    }

    @Test
    fun `a name deep in a sentence is not a wake phrase and is left in the text`() {
        val match = matchWakePhrase("I was telling Harry about the weather")
        assertFalse(match.nameMatched)
        assertEquals("I was telling Harry about the weather", match.text)
    }

    @Test
    fun `a stray word from the pre-roll before the wake phrase is still a match`() {
        val match = matchWakePhrase("um hey ari what time is it")
        assertTrue(match.nameMatched)
        assertEquals("what time is it", match.text)
    }

    @Test
    fun `cloud transcription's fused heyari is a match`() {
        // Real capture, 2026-09-12: cloud STT writes the phrase as one word.
        val match = matchWakePhrase("Heyari, what time is it?")
        assertTrue(match.nameMatched)
        assertEquals("what time is it?", match.text)
    }

    @Test
    fun `unrelated speech reports no name match and is left alone`() {
        val match = matchWakePhrase("i was talking to dave about it")
        assertFalse(match.nameMatched)
        assertEquals("i was talking to dave about it", match.text)
    }

    @Test
    fun `bare wake phrase reports a name match and empties the text`() {
        val match = matchWakePhrase("hey ari")
        assertTrue(match.nameMatched)
        assertEquals("", match.text)
    }

    @Test
    fun `ok opener with name reports a name match`() {
        val match = matchWakePhrase("ok ari whats the time")
        assertTrue(match.nameMatched)
        assertEquals("whats the time", match.text)
    }

    @Test
    fun `empty input reports no name match`() {
        val match = matchWakePhrase("")
        assertFalse(match.nameMatched)
        assertEquals("", match.text)
    }

    @Test
    fun `stripWakePhrase still returns just the text`() {
        assertEquals("whats the weather", stripWakePhrase("hey ari whats the weather"))
        assertEquals("so whats the weather", stripWakePhrase("okay so whats the weather"))
    }

    @Test
    fun `english forms a verdict from the name match`() {
        assertEquals(true, wakeVerdict(matchWakePhrase("hey ari whats the weather"), "en"))
        assertEquals(false, wakeVerdict(matchWakePhrase("hey there mate"), "en"))
    }

    @Test
    fun `non-english forms no verdict at all`() {
        // The name list was built from English sherpa mishears and
        // WakeMishearTable is still empty everywhere else, so outside English
        // we have no evidence to reject on. Null makes shouldAcceptWake fail
        // open. Deleting the locale check here would silently start dismissing
        // Italian turns — that is what this test exists to stop.
        assertNull(wakeVerdict(matchWakePhrase("hey there mate"), "it"))
        assertNull(wakeVerdict(matchWakePhrase("hey ari che tempo fa"), "it"))
    }
}
