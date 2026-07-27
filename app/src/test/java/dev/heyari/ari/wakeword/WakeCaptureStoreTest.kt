package dev.heyari.ari.wakeword

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WakeCaptureStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun le(bytes: ByteArray, offset: Int, length: Int): Int =
        ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN).let {
            if (length == 2) it.short.toInt() else it.int
        }

    @Test
    fun `wav header describes 16 bit mono 16 kHz PCM`() {
        val pcm = ShortArray(800) { it.toShort() }
        val out = wavBytes(pcm)

        assertEquals(44 + 1600, out.size)
        assertEquals("RIFF", String(out, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + 1600, le(out, 4, 4))
        assertEquals("WAVE", String(out, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(out, 12, 4, Charsets.US_ASCII))
        assertEquals(16, le(out, 16, 4))
        assertEquals(1, le(out, 20, 2))
        assertEquals(1, le(out, 22, 2))
        assertEquals(16000, le(out, 24, 4))
        assertEquals(32000, le(out, 28, 4))
        assertEquals(2, le(out, 32, 2))
        assertEquals(16, le(out, 34, 2))
        assertEquals("data", String(out, 36, 4, Charsets.US_ASCII))
        assertEquals(1600, le(out, 40, 4))
    }

    @Test
    fun `wav samples are little endian and round trip`() {
        val out = wavBytes(shortArrayOf(0, 1, -1, 258))
        assertEquals(0, le(out, 44, 2))
        assertEquals(1, le(out, 46, 2))
        assertEquals(-1, le(out, 48, 2))
        assertEquals(258, le(out, 50, 2))
    }

    @Test
    fun `eviction drops the oldest clips past the file cap`() {
        val dir = temp.newFolder("captures")
        for (i in 1..5) {
            File(dir, "wake-0000000000000000$i-rejected.wav").writeBytes(ByteArray(10))
            File(dir, "wake-0000000000000000$i-rejected.txt").writeText("clip $i")
        }

        evictOldest(dir, maxFiles = 3, maxBytes = Long.MAX_VALUE)

        val remaining = dir.listFiles { f -> f.extension == "wav" }!!.map { it.name }.sorted()
        assertEquals(
            listOf(
                "wake-00000000000000003-rejected.wav",
                "wake-00000000000000004-rejected.wav",
                "wake-00000000000000005-rejected.wav",
            ),
            remaining,
        )
        assertEquals(0, dir.listFiles { f -> f.name.startsWith("wake-00000000000000001") }!!.size)
    }

    @Test
    fun `eviction drops the oldest clips past the byte cap`() {
        val dir = temp.newFolder("captures")
        for (i in 1..4) {
            File(dir, "wake-0000000000000000$i-silent.wav").writeBytes(ByteArray(100))
            File(dir, "wake-0000000000000000$i-silent.txt").writeText("")
        }

        evictOldest(dir, maxFiles = Int.MAX_VALUE, maxBytes = 250)

        val remaining = dir.listFiles { f -> f.extension == "wav" }!!.map { it.name }.sorted()
        assertEquals(
            listOf(
                "wake-00000000000000003-silent.wav",
                "wake-00000000000000004-silent.wav",
            ),
            remaining,
        )
    }

    @Test
    fun `eviction leaves an under-cap directory alone`() {
        val dir = temp.newFolder("captures")
        File(dir, "wake-00000000000000001-rejected.wav").writeBytes(ByteArray(10))
        File(dir, "wake-00000000000000001-rejected.txt").writeText("only one")

        evictOldest(dir, maxFiles = 50, maxBytes = 20L * 1024 * 1024)

        assertEquals(1, dir.listFiles { f -> f.extension == "wav" }!!.size)
        assertEquals("only one", File(dir, "wake-00000000000000001-rejected.txt").readText())
    }
}
