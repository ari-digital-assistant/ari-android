package dev.heyari.ari.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class QuietListenTest {

    @Test
    fun `a short loud stretch inside a long quiet pre-roll is heard`() {
        // 2 s of room tone at 100 with 0.6 s of speech at 2000: averaged over the
        // whole chunk it sits under the 600 speech level, its loudest frame does not.
        val preroll = ShortArray(32_000) { i -> if (i in 20_000 until 29_600) (if (i % 2 == 0) 2000 else -2000).toShort() else (if (i % 2 == 0) 100 else -100).toShort() }

        assertEquals(2000f, SpeechRecognizer.loudestFrameRms(preroll), 0.01f)
    }

    @Test
    fun `a 30 ms live chunk scores the same as before`() {
        val chunk = ShortArray(480) { i -> if (i % 2 == 0) 700 else -700 }

        assertEquals(700f, SpeechRecognizer.loudestFrameRms(chunk), 0.01f)
    }
}
