package dev.heyari.ari.wakeword

import org.junit.Assert.assertEquals
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
        val sidecar = wakeSampleSidecar(segment, 1_000L, 94_200L, ZoneId.of("UTC"))

        assertEquals(
            """
            when: 1970-01-01T00:00:01Z
            set: Gail
            phrase: Hey Ari
            room: Living Room
            distance: across-room
            background: tv
            durationMs: 94200
            app: 0.1.0

            """.trimIndent(),
            sidecar,
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
}
