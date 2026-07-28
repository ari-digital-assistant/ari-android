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
