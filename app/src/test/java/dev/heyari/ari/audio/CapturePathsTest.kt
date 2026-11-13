package dev.heyari.ari.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A clip directory missing from `capture_paths.xml` costs the user their
 * recordings: `FileProvider.getUriForFile` throws, the throw happens inside the
 * Export click handler, and the app dies with the only copy of the audio still
 * trapped in app-private storage. `wake-samples` shipped without its entry and
 * did exactly that.
 *
 * Both directions are checked — an undeclared store crashes Export, a declared
 * directory no store writes to is a stale grant.
 *
 * Gradle does not treat the XML as an input to this task, so an edit to it
 * alone leaves the task UP-TO-DATE. A clean build catches that; a local rerun
 * needs --rerun-tasks.
 */
class CapturePathsTest {

    private val sources: List<String> =
        File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .filter { it.contains("AudioClipStore(") }
            .toList()

    private val storeDirectories: Set<String> =
        sources.flatMap { Regex("""DIR_NAME = "([^"]+)"""").findAll(it).toList() }
            .map { it.groupValues[1] }
            .toSet()

    private val declaredPaths: Set<String> =
        Regex("""path="([^"]+)/"""")
            .findAll(File("src/main/res/xml/capture_paths.xml").readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun theScanFindsEveryStore() {
        // Guards the test itself: a refactor that renames the constants would
        // otherwise leave it passing against an empty set.
        assertEquals(
            setOf(
                "wake-captures",
                "wake-captures-rejected",
                "wake-captures-accepted",
                "wake-samples",
                "utterance-captures",
            ),
            storeDirectories,
        )
    }

    @Test
    fun everyStoreDirectoryIsDeclaredForTheFileProvider() {
        storeDirectories.forEach {
            assertTrue("$it writes clips but is missing from capture_paths.xml", it in declaredPaths)
        }
    }

    @Test
    fun everyDeclaredPathBelongsToAStore() {
        declaredPaths.forEach {
            assertTrue("$it is declared in capture_paths.xml but no store writes it", it in storeDirectories)
        }
    }

    /**
     * Recordings of the user's home must not be uploaded anywhere. Both rule
     * files default to "back everything up", so a new store is included the
     * moment it exists and stays included until somebody remembers to exclude
     * it. `wake-samples` shipped that way — named recordings of four people,
     * going to Google's servers, under a settings page promising they stay on
     * the device.
     */
    @Test
    fun everyClipDirectoryIsExcludedFromBackupAndTransfer() {
        // Counted inside each block, not across the file. Counting the file as
        // a whole and expecting two is what let `wake-samples` be excluded from
        // cloud-backup twice and from device-transfer not at all — the rules
        // and the test agreed, and a new-phone migration still copied named
        // recordings of the household across.
        val extraction = File("src/main/res/xml/data_extraction_rules.xml").readText()
        val blocks = mapOf(
            "backup_rules.xml" to File("src/main/res/xml/backup_rules.xml").readText(),
            "data_extraction_rules.xml <cloud-backup>" to block(extraction, "cloud-backup"),
            "data_extraction_rules.xml <device-transfer>" to block(extraction, "device-transfer"),
        )
        blocks.forEach { (name, rules) ->
            storeDirectories.forEach { directory ->
                val count = Regex("""path="$directory/"""").findAll(rules).count()
                assertEquals("$directory must be excluded exactly once in $name", 1, count)
            }
        }
    }

    /**
     * The contents of one `data_extraction_rules` block.
     *
     * Fails loudly on a missing block rather than returning "", which would
     * make every exclusion in it look absent — a test that cannot tell "the
     * rules are wrong" from "I could not read the rules" is not a check.
     */
    private fun block(xml: String, name: String): String =
        Regex("""<$name>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)
            ?.groupValues
            ?.get(1)
            ?: error("No <$name> block in data_extraction_rules.xml")
}
