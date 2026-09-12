package dev.heyari.ari.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class WakeSampleStoreTest {

    private val segment = WakeSampleSegment(
        setName = "Gail",
        phrase = "Hey Ari",
        room = "Living Room",
        distance = SampleDistance.ACROSS_ROOM,
        background = SampleBackground.TELEVISION,
    )

    @Test
    fun `sidecar records every condition the split needs`() {
        val sidecar = wakeSampleSidecar(segment, 1_000L, 94_200L, listOf(1_200L, 5_400L), ZoneId.of("UTC"))

        assertEquals(
            """
            when: 1970-01-01T00:00:01Z
            set: Gail
            phrase: Hey Ari
            room: Living Room
            distance: across-room
            background: tv
            durationMs: 94200
            marks: 1200,5400
            app: 0.1.0

            """.trimIndent(),
            sidecar,
        )
    }

    @Test
    fun `an unmarked segment writes no marks line at all`() {
        // Not "marks: " with nothing after it: a reader has to be able to tell
        // "nobody tapped the button" from "the speaker said it zero times".
        val sidecar = wakeSampleSidecar(segment, 1_000L, 94_200L, emptyList(), ZoneId.of("UTC"))

        assertFalse(sidecar.contains("marks"))
        assertEquals(emptyList<Long>(), parseWakeSampleSidecar("sample-x", sidecar, 0L).marks)
    }

    @Test
    fun `marks survive the round trip in the order they were tapped`() {
        val sidecar = wakeSampleSidecar(segment, 1_000L, 94_200L, listOf(9_000L, 1_200L), ZoneId.of("UTC"))

        assertEquals(
            listOf(9_000L, 1_200L),
            parseWakeSampleSidecar("sample-x", sidecar, 0L).marks,
        )
    }

    @Test
    fun `slug lowercases and replaces everything that is not a letter or digit`() {
        assertEquals("living-room", sampleSlug("Living Room"))
        assertEquals("gail", sampleSlug("  Gail  "))
        assertEquals("kid-s-bedroom", sampleSlug("Kid's Bedroom"))
    }

    @Test
    fun `slug keeps non-ASCII letters rather than collapsing them to dashes`() {
        assertEquals("cucina-però", sampleSlug("Cucina però"))
    }

    @Test
    fun `slug never yields an empty stem, and never leads or trails with a dash`() {
        assertEquals("unnamed", sampleSlug(""))
        assertEquals("unnamed", sampleSlug("!!!"))
        assertEquals("hall", sampleSlug("--Hall--"))
    }

    @Test
    fun `slug is capped so one long room name cannot dominate the filename`() {
        assertEquals(24, sampleSlug("a".repeat(80)).length)
    }

    @Test
    fun `a sidecar parses back into the summary the settings list shows`() {
        val sidecar = wakeSampleSidecar(segment, 1_000L, 94_200L, emptyList(), ZoneId.of("UTC"))

        val summary = parseWakeSampleSidecar("sample-x", sidecar, fallbackMs = 0L)

        assertEquals("Gail", summary.setName)
        assertEquals("Living Room", summary.room)
        assertEquals(SampleDistance.ACROSS_ROOM, summary.distance)
        assertEquals(SampleBackground.TELEVISION, summary.background)
        assertEquals(94_200L, summary.durationMs)
        assertEquals(1_000L, summary.recordedAtMs)
    }

    @Test
    fun `an unknown condition keeps its slug instead of vanishing from the list`() {
        val sidecar = wakeSampleSidecar(segment, 1_000L, 1L, emptyList(), ZoneId.of("UTC"))
            .replace("distance: across-room", "distance: from-the-garden")

        val summary = parseWakeSampleSidecar("sample-x", sidecar, fallbackMs = 0L)

        assertNull(summary.distance)
        assertEquals("from-the-garden", summary.distanceSlug)
        assertEquals("Gail", summary.setName)
    }

    @Test
    fun `a sidecar with no timestamp falls back to the file's own time`() {
        val summary = parseWakeSampleSidecar("sample-x", "set: Gail\n", fallbackMs = 4_242L)

        assertEquals(4_242L, summary.recordedAtMs)
        assertEquals(0L, summary.durationMs)
    }
}
