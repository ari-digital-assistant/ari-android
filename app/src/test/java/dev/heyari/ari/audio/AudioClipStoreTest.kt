package dev.heyari.ari.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The WAV and its eviction are the engine's, tested there (ari-voice `captures`). */
class AudioClipStoreTest {

    @Test
    fun `stems are fixed-width so lexicographic order is chronological`() {
        assertEquals(
            "utterance-00000001769000000-rescued",
            clipStem("utterance", 1_769_000_000L, "rescued"),
        )
        val older = clipStem("wake", 999L, "silent")
        val newer = clipStem("wake", 1_000L, "silent")
        assertEquals("wake-00000000000000999-silent", older)
        assertEquals(true, older < newer)
    }

    @Test
    fun `stems use ASCII digits regardless of the default locale`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
        try {
            assertEquals("wake-00000000000000042-rejected", clipStem("wake", 42L, "rejected"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
