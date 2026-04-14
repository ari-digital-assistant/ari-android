package dev.heyari.ari.assets

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

class AssetResolverTest {

    private lateinit var skillsRoot: File

    @Before
    fun setUp() {
        skillsRoot = createTempDir(prefix = "ari-skills-test-")
    }

    @After
    fun tearDown() {
        skillsRoot.deleteRecursively()
    }

    private fun createInstalledSkill(slug: String, vararg assetPaths: String) {
        val skillDir = File(skillsRoot, slug).apply { mkdirs() }
        for (path in assetPaths) {
            val file = File(skillDir, "assets/$path")
            file.parentFile?.mkdirs()
            file.writeText("test")
        }
    }

    @Test
    fun resolvesSimpleAsset() {
        createInstalledSkill("timer", "timer_ding.wav")
        val resolved = resolveAssetFile(skillsRoot, "dev.heyari.timer", "asset:timer_ding.wav")
        assertEquals(File(skillsRoot, "timer/assets/timer_ding.wav").canonicalPath, resolved!!.canonicalPath)
    }

    @Test
    fun resolvesNestedAsset() {
        createInstalledSkill("timer", "icons/timer.png")
        val resolved = resolveAssetFile(skillsRoot, "dev.heyari.timer", "asset:icons/timer.png")
        assertEquals("test", resolved!!.readText())
    }

    @Test
    fun returnsNullForNonAssetReference() {
        // A skill might pass `system.alarm` or some other token through —
        // not our problem to resolve, just return null.
        assertNull(resolveAssetFile(skillsRoot, "dev.heyari.timer", "system.alarm"))
        assertNull(resolveAssetFile(skillsRoot, "dev.heyari.timer", null))
    }

    @Test
    fun returnsNullForBuiltinSkillId() {
        // Built-in skill ids (no dots) have no install dir.
        createInstalledSkill("open")  // shouldn't matter
        assertNull(resolveAssetFile(skillsRoot, "open", "asset:icon.png"))
    }

    @Test
    fun returnsNullForUninstalledSkill() {
        // dev.heyari.timer has no `timer` dir.
        assertNull(resolveAssetFile(skillsRoot, "dev.heyari.timer", "asset:icon.png"))
    }

    @Test
    fun returnsNullForMissingFile() {
        createInstalledSkill("timer")  // dir exists, asset doesn't
        assertNull(resolveAssetFile(skillsRoot, "dev.heyari.timer", "asset:missing.wav"))
    }

    @Test
    fun rejectsPathTraversal() {
        // Defence-in-depth: bundle extractor already filters these at
        // install, but a maliciously crafted runtime envelope still gets
        // told no.
        createInstalledSkill("timer", "ok.wav")
        // sibling dir
        File(skillsRoot, "other-skill/secret.txt").apply { parentFile?.mkdirs(); writeText("x") }
        assertNull(resolveAssetFile(skillsRoot, "dev.heyari.timer", "asset:../../other-skill/secret.txt"))
    }

    @Test
    fun rejectsAbsolutePath() {
        createInstalledSkill("timer", "ok.wav")
        // `asset:/etc/passwd` after the prefix strip is `/etc/passwd`. Even
        // though File(assetsDir, "/etc/passwd") collapses to /etc/passwd,
        // the escape check rejects it.
        assertNull(resolveAssetFile(skillsRoot, "dev.heyari.timer", "asset:/etc/passwd"))
    }
}
