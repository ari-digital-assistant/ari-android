package dev.heyari.ari.wakeword

import dev.heyari.ari.audio.ClipStats
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The settings page's view of the wake captures the engine writes. Which
 * directory a clip goes to is the engine's, tested there (ari-voice
 * `captures`).
 */
class WakeCaptureStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = WakeCaptureStore(null, temp.root)

    private fun names(dirName: String): List<String> =
        File(temp.root, dirName).listFiles()?.map { it.name }?.sorted() ?: emptyList()

    /** A clip as the engine leaves it: a WAV of [bytes] and its sidecar. */
    private fun clip(dirName: String, stem: String, bytes: Int) {
        val dir = File(temp.root, dirName).apply { mkdirs() }
        File(dir, "$stem.wav").writeBytes(ByteArray(bytes))
        File(dir, "$stem.txt").writeText("")
    }

    @Test
    fun `stats and clear span all three directories`() {
        clip("wake-captures-rejected", "wake-00000000000001000-rejected", 3_244)
        clip("wake-captures", "wake-00000000000002000-silent", 3_244)
        clip("wake-captures-accepted", "wake-00000000000003000-accepted", 3_244)
        val store = store()

        assertEquals(ClipStats(count = 3, totalBytes = 9732L), store.stats())
        assertEquals(6, store.files().size)

        store.clear()

        assertEquals(ClipStats(count = 0, totalBytes = 0L), store.stats())
        assertEquals(emptyList<String>(), names("wake-captures-rejected"))
        assertEquals(emptyList<String>(), names("wake-captures"))
        assertEquals(emptyList<String>(), names("wake-captures-accepted"))
    }
}
