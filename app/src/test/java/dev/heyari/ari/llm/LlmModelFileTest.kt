package dev.heyari.ari.llm

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [LlmDownloadManager.modelFileIn]: a tier's own file, or the GGUF it had
 * before ari-android#52, which stands in until that file arrives.
 */
class LlmModelFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val model = LlmModelRegistry.MEDIUM

    private fun dir(vararg files: String): File =
        tmp.newFolder().also { dir -> files.forEach { File(dir, it).writeText("x") } }

    @Test
    fun theTiersOwnFileWins() {
        val dir = dir(model.fileName, model.legacyFileName!!)
        assertEquals(File(dir, "gemma-4-E2B-it.litertlm"), LlmDownloadManager.modelFileIn(dir, model))
    }

    @Test
    fun theLegacyGgufStandsInUntilItsReplacementArrives() {
        val dir = dir(model.legacyFileName!!)
        assertEquals(File(dir, "gemma-4-E2B-it-Q4_K_M.gguf"), LlmDownloadManager.modelFileIn(dir, model))
    }

    @Test
    fun withNeitherItIsTheTiersOwnFileThatIsMissing() {
        val dir = dir()
        val file = LlmDownloadManager.modelFileIn(dir, model)
        assertEquals(File(dir, "gemma-4-E2B-it.litertlm"), file)
        assertEquals(false, file.isFile)
    }

    @Test
    fun aPartialDownloadIsNotTheTiersFile() {
        val dir = dir("${model.fileName}.e79a6c3fc0fd.part", model.legacyFileName!!)
        assertEquals(File(dir, "gemma-4-E2B-it-Q4_K_M.gguf"), LlmDownloadManager.modelFileIn(dir, model))
    }

    @Test
    fun everyTierRunsOnLiteRtAndRemembersItsGguf() {
        for (tier in LlmModelRegistry.all) {
            assertEquals(true, tier.fileName.endsWith(".litertlm") || tier.fileName.endsWith(".task"))
            assertEquals(true, tier.legacyFileName!!.endsWith(".gguf"))
            assertEquals(true, tier.manifestUrl.contains("-litert-latest/"))
        }
    }
}
