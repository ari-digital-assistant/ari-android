package dev.heyari.ari.updates

import dev.heyari.ari.R
import dev.heyari.ari.di.EngineModule
import dev.heyari.ari.llm.LlmModelRegistry
import dev.heyari.ari.models.ModelManifest
import dev.heyari.ari.models.ModelTarget
import dev.heyari.ari.models.ModelUpdate
import dev.heyari.ari.stt.SttModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `model summaries resolve the display name through the caller's resolver`() {
        // PendingUpdateSummary.displayName stays a String because it is
        // persisted and shared with skill updates, whose names come from the
        // FFI at runtime and are not resources. So the model path has to be
        // handed a resolver — if it ever went back to reading a literal off
        // the target, the name would stop translating and this would catch it.
        val update = ModelUpdate(
            target = ModelTarget.Stt(SttModelRegistry.KROKO),
            installedVersion = "1",
            manifest = ModelManifest(version = "2", releasedAt = null, files = emptyList()),
        )
        val summaries = UpdatesRepository.summariesFromModelUpdates(listOf(update)) { res ->
            assertEquals(SttModelRegistry.KROKO.displayNameRes, res)
            "resolved-by-caller"
        }
        assertEquals(1, summaries.size)
        assertEquals("resolved-by-caller", summaries[0].displayName)
        assertEquals(SttModelRegistry.KROKO.id, summaries[0].id)
        assertEquals("1", summaries[0].installedVersion)
        assertEquals("2", summaries[0].availableVersion)
    }

    @Test
    fun `display names resolve from the stable key, not a persisted resource id`() {
        // Resource ids move between builds, so persisting one would silently
        // point at a different string after an app update. Keys are ours and
        // stable, which is why the summaries store the key.
        assertEquals(
            SttModelRegistry.KROKO.displayNameRes,
            ModelTarget.displayNameResFor(SttModelRegistry.KROKO.id),
        )
        assertEquals(
            LlmModelRegistry.SMALL.displayNameRes,
            ModelTarget.displayNameResFor(LlmModelRegistry.SMALL.id),
        )
        assertEquals(
            R.string.model_router_name,
            ModelTarget.displayNameResFor(EngineModule.ROUTER_MODEL_KEY),
        )
    }

    @Test
    fun `a retired model's key resolves to null so the caller can fall back`() {
        // A summary persisted before Nemotron was retired names something this
        // build cannot resolve. Null is the signal to use the stored text; a
        // crash or a blank name would both be worse.
        assertNull(ModelTarget.displayNameResFor("nemotron-0.6b-int8-2026-01-14"))
        assertNull(ModelTarget.displayNameResFor("not-a-model"))
    }
}
