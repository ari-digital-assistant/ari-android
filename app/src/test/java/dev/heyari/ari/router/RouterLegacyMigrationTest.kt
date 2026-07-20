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
    fun missingSidecarAdoptsWithNoSidecarAndReadsAsUnknown() {
        val root = temp.newFolder("router")
        seedLegacyInstall(root, withSidecar = false)

        val result = RouterLegacyMigration.migrate(root, "en")

        assertEquals(LegacyMigrationResult.ADOPTED, result)
        val enDir = File(root, "en")
        assertEquals("gguf-bytes", File(enDir, "ari-functiongemma-en-q4_k_m.gguf").readText())
        // Nothing to carry, so nothing is written. `unknown` is already the
        // always-stale state the update checker acts on, and manufacturing a
        // sidecar to say it would mean hashing 253 MB during startup.
        assertFalse(File(enDir, InstalledModelMetadata.SIDECAR_FILENAME).exists())
        assertEquals(InstalledModelMetadata.UNKNOWN_VERSION, InstalledModelMetadata.readVersion(enDir))
    }

    @Test
    fun blockedMoveFailsWithoutLosingTheLegacyInstall() {
        val root = temp.newFolder("router")
        seedLegacyInstall(root)
        // A plain file where the locale directory belongs: mkdirs() can't
        // create it and renameTo has nowhere to land.
        File(root, "en").writeText("not a directory")

        val result = RouterLegacyMigration.migrate(root, "en")

        assertEquals(LegacyMigrationResult.FAILED, result)
        // The legacy install is intact, so the next start retries it rather
        // than facing a 253 MB re-download.
        assertEquals("gguf-bytes", File(root, RouterModel.LEGACY_FILENAME).readText())
        assertEquals("r100", InstalledModelMetadata.read(root)!!.version)
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
    fun theLocaleArgumentNeverConstructsADestinationPath() {
        val root = temp.newFolder("router")
        seedLegacyInstall(root)

        val result = RouterLegacyMigration.migrate(root, "../evil")

        assertEquals(LegacyMigrationResult.DISCARDED, result)
        // The destination is a hardcoded constant, never the argument. A
        // locale only ever picks between "adopt as en" and "delete" — if a
        // refactor ever derives the target directory from the parameter, this
        // is where it gets caught.
        assertEquals(emptyList<String>(), root.list()!!.toList())
        assertFalse(File(root.parentFile, "evil").exists())
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
