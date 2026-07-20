package dev.heyari.ari.router

import dev.heyari.ari.models.InstalledModelMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for the one-shot adoption of a pre-per-locale router install.
 *
 * The stakes: getting this wrong either strands 253 MB of orphaned bytes on
 * disk, or — far worse — files an English model under a non-English locale,
 * which is the one thing the router is never allowed to do.
 */
class RouterLegacyMigrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val legacySha = "abc123"

    private fun seedLegacyInstall(root: File, withSidecar: Boolean = true) {
        File(root, RouterModel.LEGACY_FILENAME).writeText("gguf-bytes")
        if (withSidecar) {
            InstalledModelMetadata.writeSingle(
                root,
                version = "r100",
                fileName = RouterModel.LEGACY_FILENAME,
                sha256 = legacySha,
            )
        }
    }

    @Test
    fun englishInstallIsAdoptedInPlace() {
        val root = temp.newFolder("router")
        seedLegacyInstall(root)

        val result = RouterLegacyMigration.migrate(root, "en")

        assertEquals(LegacyMigrationResult.ADOPTED, result)
        val adopted = File(File(root, "en"), "ari-functiongemma-en-q4_k_m.gguf")
        assertTrue(adopted.isFile)
        assertEquals("gguf-bytes", adopted.readText())
        assertFalse(File(root, RouterModel.LEGACY_FILENAME).exists())
    }

    @Test
    fun adoptedSidecarKeepsVersionAndShaButTakesNewName() {
        val root = temp.newFolder("router")
        seedLegacyInstall(root)

        RouterLegacyMigration.migrate(root, "en")

        val moved = InstalledModelMetadata.read(File(root, "en"))!!
        // Version carries over so the update checker sees r100 as stale and
        // offers the real -en- model rather than believing it's current.
        assertEquals("r100", moved.version)
        assertEquals(legacySha, moved.files.single().sha256)
        assertEquals("ari-functiongemma-en-q4_k_m.gguf", moved.files.single().name)
        assertFalse(File(root, InstalledModelMetadata.SIDECAR_FILENAME).exists())
    }

    @Test
    fun missingSidecarAdoptsAsUnknownVersion() {
        val root = temp.newFolder("router")
        seedLegacyInstall(root, withSidecar = false)

        val result = RouterLegacyMigration.migrate(root, "en")

        assertEquals(LegacyMigrationResult.ADOPTED, result)
        val moved = InstalledModelMetadata.read(File(root, "en"))!!
        assertEquals(InstalledModelMetadata.UNKNOWN_VERSION, moved.version)
        // Sha is recomputed from the real bytes, not invented.
        assertEquals(
            InstalledModelMetadata.sha256Hex(File(File(root, "en"), "ari-functiongemma-en-q4_k_m.gguf")),
            moved.files.single().sha256,
        )
    }

    @Test
    fun nonEnglishInstallDiscardsLegacyRatherThanAdoptingIt() {
        val root = temp.newFolder("router")
        seedLegacyInstall(root)

        val result = RouterLegacyMigration.migrate(root, "it")

        assertEquals(LegacyMigrationResult.DISCARDED, result)
        assertFalse(File(root, RouterModel.LEGACY_FILENAME).exists())
        assertFalse(File(root, InstalledModelMetadata.SIDECAR_FILENAME).exists())
        // The English model must never appear under another locale.
        assertFalse(File(root, "it").exists())
        assertFalse(File(root, "en").exists())
    }

    @Test
    fun noLegacyFileIsNothingToDo() {
        val root = temp.newFolder("router")

        assertEquals(LegacyMigrationResult.NOTHING_TO_DO, RouterLegacyMigration.migrate(root, "en"))
    }

    @Test
    fun secondRunIsNothingToDo() {
        val root = temp.newFolder("router")
        seedLegacyInstall(root)

        assertEquals(LegacyMigrationResult.ADOPTED, RouterLegacyMigration.migrate(root, "en"))
        assertEquals(LegacyMigrationResult.NOTHING_TO_DO, RouterLegacyMigration.migrate(root, "en"))
        // The first run's work survives the second.
        assertTrue(File(File(root, "en"), "ari-functiongemma-en-q4_k_m.gguf").isFile)
    }

    @Test
    fun adoptionDoesNotClobberAnAlreadyInstalledEnglishModel() {
        val root = temp.newFolder("router")
        seedLegacyInstall(root)
        val enDir = File(root, "en").apply { mkdirs() }
        File(enDir, "ari-functiongemma-en-q4_k_m.gguf").writeText("newer-model")
        InstalledModelMetadata.writeSingle(enDir, "r250", "ari-functiongemma-en-q4_k_m.gguf", "def456")

        val result = RouterLegacyMigration.migrate(root, "en")

        // The per-locale model already won; legacy is just stale bytes.
        assertEquals(LegacyMigrationResult.DISCARDED, result)
        assertEquals("newer-model", File(enDir, "ari-functiongemma-en-q4_k_m.gguf").readText())
        assertEquals("r250", InstalledModelMetadata.read(enDir)!!.version)
        assertFalse(File(root, RouterModel.LEGACY_FILENAME).exists())
    }
}
