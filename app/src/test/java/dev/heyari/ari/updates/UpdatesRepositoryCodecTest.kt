package dev.heyari.ari.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the encode/decode/fingerprint round-trip used by
 * [UpdatesRepository] to persist pending updates in DataStore.
 *
 * The encoding is hand-rolled (NSV with Unicode SOH/STX separators) so we
 * lock in the contract: round-trip preserves every field, fingerprint is
 * stable under reordering, and a different version flips the fingerprint.
 */
class UpdatesRepositoryCodecTest {

    private val a = PendingUpdateSummary(
        id = "dev.heyari.timer",
        displayName = "Timer",
        installedVersion = "1.2.0",
        availableVersion = "1.3.0",
    )
    private val b = PendingUpdateSummary(
        id = "dev.heyari.weather",
        displayName = "Weather",
        installedVersion = "0.9.0",
        availableVersion = "1.0.0",
    )

    @Test
    fun `encode then decode preserves all fields`() {
        val encoded = UpdatesRepository.encode(listOf(a, b))
        val decoded = UpdatesRepository.decode(encoded)
        assertEquals(listOf(a, b), decoded)
    }

    @Test
    fun `decode of empty payload returns empty list`() {
        assertEquals(emptyList<PendingUpdateSummary>(), UpdatesRepository.decode(null))
        assertEquals(emptyList<PendingUpdateSummary>(), UpdatesRepository.decode(""))
    }

    @Test
    fun `fingerprint is stable across order`() {
        // The user-facing list might be sorted differently than the
        // worker's emit order; the gate must treat both as the same set.
        val fp1 = UpdatesRepository.fingerprint(listOf(a, b))
        val fp2 = UpdatesRepository.fingerprint(listOf(b, a))
        assertEquals(fp1, fp2)
    }

    @Test
    fun `fingerprint flips when an available version changes`() {
        val fp1 = UpdatesRepository.fingerprint(listOf(a))
        val fp2 = UpdatesRepository.fingerprint(listOf(a.copy(availableVersion = "1.4.0")))
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `fingerprint ignores installed version differences`() {
        // Whether the user is on 1.2.0 or 1.2.1 of the existing build, if
        // the available version is the same, it's still the same "ready
        // for v1.3.0" update set. This keeps re-notifies to a minimum
        // when only the installed side moves.
        val fp1 = UpdatesRepository.fingerprint(listOf(a))
        val fp2 = UpdatesRepository.fingerprint(listOf(a.copy(installedVersion = "1.2.1")))
        assertEquals(fp1, fp2)
    }

    @Test
    fun `fingerprint changes when set membership changes`() {
        val fp1 = UpdatesRepository.fingerprint(listOf(a))
        val fp2 = UpdatesRepository.fingerprint(listOf(a, b))
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `fingerprint of empty list is empty string`() {
        assertEquals("", UpdatesRepository.fingerprint(emptyList()))
    }

    @Test
    fun `decode tolerates malformed rows by skipping them`() {
        // If a future version persists an extra field, older builds should
        // skip the unparseable row rather than crashing or leaking a
        // partial summary.
        val good = UpdatesRepository.encode(listOf(a))
        val malformed = good + "not-a-valid-row"
        val decoded = UpdatesRepository.decode(malformed)
        assertEquals(listOf(a), decoded)
        assertTrue(decoded.isNotEmpty())
        assertFalse(decoded.any { it.id == "not-a-valid-row" })
    }
}
