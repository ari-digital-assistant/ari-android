package dev.heyari.ari.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillScreenshotCacheTest {

    private val base = "https://raw.githubusercontent.com/ari-digital-assistant/ari-skills/main/"

    @Test
    fun `flattens a registry url into one legible filename`() {
        assertEquals(
            "raw.githubusercontent.com_ari-digital-assistant_ari-skills_main_screenshots_dev.heyari.timer-0.2.0_android_01-set.webp",
            cacheFileName("${base}screenshots/dev.heyari.timer-0.2.0/android/01-set.webp"),
        )
    }

    @Test
    fun `different screenshots of the same skill get different names`() {
        val one = cacheFileName("${base}screenshots/dev.heyari.timer-0.2.0/android/01-set.webp")
        val two = cacheFileName("${base}screenshots/dev.heyari.timer-0.2.0/android/02-list.webp")
        assertNotEquals(one, two)
    }

    @Test
    fun `a new skill version does not reuse the previous version's cache entry`() {
        val old = cacheFileName("${base}screenshots/dev.heyari.timer-0.2.0/android/01-set.webp")
        val new = cacheFileName("${base}screenshots/dev.heyari.timer-0.3.0/android/01-set.webp")
        assertNotEquals(old, new)
    }

    @Test
    fun `the same shot on two platforms does not collide`() {
        val android = cacheFileName("${base}screenshots/dev.heyari.timer-0.2.0/android/01-set.png")
        val linux = cacheFileName("${base}screenshots/dev.heyari.timer-0.2.0/linux/01-set.png")
        assertNotEquals(android, linux)
    }

    @Test
    fun `path separators and query strings never escape the cache directory`() {
        val name = cacheFileName("https://evil.test/../../etc/passwd?x=/../y")
        assertTrue("must stay a single path segment, got $name", !name.contains('/'))
        assertEquals("evil.test_.._.._etc_passwd_x__.._y", name)
    }

    @Test
    fun `an absurdly long url is truncated but keeps its distinctive tail`() {
        val long = "$base${"deep/".repeat(80)}screenshots/dev.heyari.timer-0.2.0/android/01-set.webp"
        val name = cacheFileName(long)
        assertEquals(120, name.length)
        assertTrue("tail must survive truncation, got $name", name.endsWith("android_01-set.webp"))
    }
}
