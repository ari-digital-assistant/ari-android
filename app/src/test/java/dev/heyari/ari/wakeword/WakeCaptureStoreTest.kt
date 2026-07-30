package dev.heyari.ari.wakeword

import dev.heyari.ari.audio.ClipStats
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WakeCaptureStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val pcm = ShortArray(1600) { 100 }

    private fun store() = WakeCaptureStore(null, temp.root)

    private fun names(dirName: String): List<String> =
        File(temp.root, dirName).listFiles()?.map { it.name }?.sorted() ?: emptyList()

    @Test
    fun `rejected clips land in the quarantine dir, silent ones in the retrain feed`() {
        val store = store()

        store.save(pcm, "ARA reminds me", WakeCaptureHook.REJECTED, 1_000L)
        store.save(pcm, "", WakeCaptureHook.SILENT, 2_000L)

        assertEquals(
            listOf(
                "wake-00000000000001000-rejected.txt",
                "wake-00000000000001000-rejected.wav",
            ),
            names("wake-captures-rejected"),
        )
        assertEquals(
            listOf(
                "wake-00000000000002000-silent.txt",
                "wake-00000000000002000-silent.wav",
            ),
            names("wake-captures"),
        )
        // The transcript that got the clip rejected travels with it, so a human
        // reviewing the quarantine can see what sherpa thought it heard.
        assertEquals(
            "ARA reminds me",
            File(temp.root, "wake-captures-rejected/wake-00000000000001000-rejected.txt").readText(),
        )
    }

    @Test
    fun `accepted clips land in their own dir, never the retrain feed or quarantine`() {
        val store = store()

        store.save(pcm, "hey ari how s the weather", WakeCaptureHook.ACCEPTED, 3_000L)

        assertEquals(
            listOf(
                "wake-00000000000003000-accepted.txt",
                "wake-00000000000003000-accepted.wav",
            ),
            names("wake-captures-accepted"),
        )
        assertEquals(emptyList<String>(), names("wake-captures"))
        assertEquals(emptyList<String>(), names("wake-captures-rejected"))
    }

    @Test
    fun `stats and clear span all three directories`() {
        val store = store()
        store.save(pcm, "ARA reminds me", WakeCaptureHook.REJECTED, 1_000L)
        store.save(pcm, "", WakeCaptureHook.SILENT, 2_000L)
        store.save(pcm, "hey ari how s the weather", WakeCaptureHook.ACCEPTED, 3_000L)

        // Three 1600-sample WAVs: 44 header bytes + 3200 data bytes each.
        assertEquals(ClipStats(count = 3, totalBytes = 9732L), store.stats())

        store.clear()

        assertEquals(ClipStats(count = 0, totalBytes = 0L), store.stats())
        assertEquals(emptyList<String>(), names("wake-captures-rejected"))
        assertEquals(emptyList<String>(), names("wake-captures"))
        assertEquals(emptyList<String>(), names("wake-captures-accepted"))
    }
}
