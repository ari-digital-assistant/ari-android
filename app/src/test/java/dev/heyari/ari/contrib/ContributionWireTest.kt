package dev.heyari.ari.contrib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContributionWireTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun clip(
        category: ContributionCategory,
        stem: String,
        audioBytes: Int,
        sidecarBytes: Int,
    ): ContributionClip {
        val audio = File(temp.root, "$stem.wav").apply { writeBytes(ByteArray(audioBytes)) }
        val sidecar = File(temp.root, "$stem.txt").apply { writeBytes(ByteArray(sidecarBytes)) }
        return ContributionClip(category, stem, audio, sidecar)
    }

    private fun batch(vararg clips: ContributionClip) = ContributionBatch(
        contributorId = "8b1d4e70-9c2a-4f31-8e55-0d6a7b9c1e23",
        locale = "en",
        clips = clips.toList(),
        lastStem = clips.last().stem,
    )

    @Test
    fun `the body names the contributor and the language`() {
        val json = batch(clip(ContributionCategory.WAKE, "wake-1", 64, 16)).toWireJson()

        assertEquals("8b1d4e70-9c2a-4f31-8e55-0d6a7b9c1e23", json.getString("contributorId"))
        assertEquals("en", json.getString("locale"))
    }

    @Test
    fun `each clip declares its audio and its sidecar with real byte counts`() {
        val json = batch(clip(ContributionCategory.FALSE_TRIGGER, "wake-9", 128, 40)).toWireJson()

        val files = json.getJSONArray("files")
        assertEquals(2, files.length())

        val audio = files.getJSONObject(0)
        assertEquals("false-trigger", audio.getString("category"))
        assertEquals("wake-9.wav", audio.getString("name"))
        assertEquals(128L, audio.getLong("bytes"))

        val sidecar = files.getJSONObject(1)
        assertEquals("false-trigger", sidecar.getString("category"))
        assertEquals("wake-9.txt", sidecar.getString("name"))
        assertEquals(40L, sidecar.getLong("bytes"))
    }

    @Test
    fun `several clips are listed in the order they are sent`() {
        val json = batch(
            clip(ContributionCategory.COMMAND, "utterance-1", 10, 5),
            clip(ContributionCategory.COMMAND, "utterance-2", 20, 6),
        ).toWireJson()

        val files = json.getJSONArray("files")
        assertEquals(4, files.length())
        assertEquals("utterance-1.wav", files.getJSONObject(0).getString("name"))
        assertEquals("utterance-1.txt", files.getJSONObject(1).getString("name"))
        assertEquals("utterance-2.wav", files.getJSONObject(2).getString("name"))
        assertEquals("utterance-2.txt", files.getJSONObject(3).getString("name"))
    }

    @Test
    fun `nothing identifying the device or the install goes on the wire`() {
        val json = batch(clip(ContributionCategory.WAKE, "wake-1", 64, 16)).toWireJson()

        assertFalse(json.has("installId"))
        assertFalse(json.has("device"))
        assertFalse(json.has("app"))
        assertEquals(setOf("contributorId", "locale", "files"), json.keys().asSequence().toSet())
    }
}
