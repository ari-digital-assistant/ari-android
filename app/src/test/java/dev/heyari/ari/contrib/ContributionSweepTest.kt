package dev.heyari.ari.contrib

import dev.heyari.ari.audio.clipStem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContributionSweepTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A clip/sidecar pair as the capture stores write them. */
    private fun clip(dir: File, timestampMs: Long, slug: String = "silent"): String {
        val stem = clipStem("wake", timestampMs, slug)
        File(dir, "$stem.wav").writeBytes(ByteArray(64))
        File(dir, "$stem.txt").writeText("when: whenever\n")
        return stem
    }

    @Test
    fun `no watermark offers every clip, oldest first`() {
        val dir = temp.newFolder("wake-captures")
        val third = clip(dir, 3_000)
        val first = clip(dir, 1_000)
        val second = clip(dir, 2_000)

        assertEquals(
            listOf(first, second, third),
            pendingClips(dir, watermark = null).map { it.stem },
        )
    }

    @Test
    fun `a watermark excludes itself and everything older`() {
        val dir = temp.newFolder("wake-captures")
        clip(dir, 1_000)
        val mark = clip(dir, 2_000)
        val newer = clip(dir, 3_000)

        assertEquals(listOf(newer), pendingClips(dir, mark).map { it.stem })
    }

    @Test
    fun `a watermark newer than everything on disk offers nothing`() {
        val dir = temp.newFolder("wake-captures")
        clip(dir, 1_000)
        val mark = clip(dir, 2_000)

        assertEquals(emptyList<String>(), pendingClips(dir, mark).map { it.stem })
    }

    @Test
    fun `audio with no sidecar is skipped rather than sent alone`() {
        val dir = temp.newFolder("wake-captures")
        val orphan = clipStem("wake", 1_000, "silent")
        File(dir, "$orphan.wav").writeBytes(ByteArray(64))
        val whole = clip(dir, 2_000)

        assertEquals(listOf(whole), pendingClips(dir, watermark = null).map { it.stem })
    }

    @Test
    fun `a directory that was never created offers nothing`() {
        val missing = File(temp.root, "utterance-captures")

        assertEquals(emptyList<ClipPair>(), pendingClips(missing, watermark = null))
    }

    @Test
    fun `each pair carries the two files that share its stem`() {
        val dir = temp.newFolder("utterance-captures")
        val stem = clip(dir, 5_000, "answered")

        val pair = pendingClips(dir, watermark = null).single()
        assertEquals(stem, pair.stem)
        assertEquals("$stem.wav", pair.audio.name)
        assertEquals("$stem.txt", pair.sidecar.name)
    }

    @Test
    fun `sealing at the newest clip leaves only later ones to send`() {
        val dir = temp.newFolder("wake-captures")
        clip(dir, 1_000)
        val newest = clip(dir, 2_000)

        // What sealExistingClips persists: the last stem on disk right now.
        val seal = pendingClips(dir, watermark = null).last().stem
        assertEquals(newest, seal)
        assertEquals(emptyList<String>(), pendingClips(dir, seal).map { it.stem })

        val afterConsent = clip(dir, 3_000)
        assertEquals(listOf(afterConsent), pendingClips(dir, seal).map { it.stem })
    }

    @Test
    fun `an empty directory has nothing to seal, so the next clip still goes`() {
        val dir = temp.newFolder("wake-captures-accepted")

        assertEquals(emptyList<ClipPair>(), pendingClips(dir, watermark = null))

        val first = clip(dir, 1_000, "accepted")
        assertEquals(listOf(first), pendingClips(dir, watermark = null).map { it.stem })
    }

    @Test
    fun `a category is contributable only when it is both kept and shared`() {
        val keep = RecordingChoices(wake = true, falseTrigger = true)
        val share = RecordingChoices(falseTrigger = true, command = true)

        assertEquals(
            listOf(ContributionCategory.FALSE_TRIGGER),
            contributableCategories(keep, share),
        )
    }

    @Test
    fun `keeping everything makes any shared category contributable`() {
        val keep = RecordingChoices(everything = true)
        val share = RecordingChoices(wake = true, command = true)

        assertEquals(
            listOf(ContributionCategory.WAKE, ContributionCategory.COMMAND),
            contributableCategories(keep, share),
        )
    }

    @Test
    fun `sharing everything still only sends what is being kept`() {
        val keep = RecordingChoices(command = true)
        val share = RecordingChoices(everything = true)

        assertEquals(
            listOf(ContributionCategory.COMMAND),
            contributableCategories(keep, share),
        )
    }

    @Test
    fun `nothing kept means nothing to contribute however much is shared`() {
        val share = RecordingChoices(wake = true, falseTrigger = true, command = true, everything = true)

        assertEquals(emptyList<ContributionCategory>(), contributableCategories(RecordingChoices(), share))
    }

    @Test
    fun `false trigger draws on both the silent and the quarantined directory`() {
        assertEquals(
            listOf("wake-captures", "wake-captures-rejected"),
            ContributionCategory.FALSE_TRIGGER.dirNames,
        )
        assertEquals(listOf("wake-captures-accepted"), ContributionCategory.WAKE.dirNames)
        assertEquals(listOf("utterance-captures"), ContributionCategory.COMMAND.dirNames)
    }
}
