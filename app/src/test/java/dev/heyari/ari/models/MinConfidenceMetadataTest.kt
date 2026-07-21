package dev.heyari.ari.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/**
 * T5: the per-model confidence floor must survive the full trip —
 * manifest JSON → [ModelManifest] → install sidecar → [InstalledVersion] —
 * and be absent (not zero, not NaN) at every stage when the manifest
 * predates Gate v4.
 */
class MinConfidenceMetadataTest {

    private val routerManifest = """
        {"version":"2026.07.21-r122",
         "url":"https://example.com/ari-functiongemma-en-q4_k_m.gguf",
         "sha256":"c79afd","size_bytes":253126848,
         "released_at":"2026-07-21T09:35:58Z",
         "min_confidence":-0.0233}
    """.trimIndent()

    @Test
    fun manifestParsesMinConfidence() {
        assertEquals(-0.0233f, ModelManifest.parse(routerManifest).minConfidence!!, 1e-6f)
    }

    @Test
    fun manifestWithoutFieldYieldsNull() {
        val legacy = ModelManifest.parse(
            """{"version":"2026.07.16-r100","url":"https://example.com/m.gguf",
                "sha256":"32bf98","size_bytes":253126848}""",
        )
        assertNull(legacy.minConfidence)
    }

    @Test
    fun sidecarRoundTripsMinConfidence() {
        val dir = Files.createTempDirectory("sidecar").toFile()
        InstalledModelMetadata.writeSingle(
            dir, version = "2026.07.21-r122", fileName = "m.gguf",
            sha256 = "c79afd", minConfidence = -0.0233f,
        )
        val read = InstalledModelMetadata.read(dir)!!
        assertEquals("2026.07.21-r122", read.version)
        assertEquals(-0.0233f, read.minConfidence!!, 1e-6f)
    }

    @Test
    fun sidecarWithoutFloorReadsNull() {
        val dir = Files.createTempDirectory("sidecar").toFile()
        InstalledModelMetadata.writeSingle(dir, version = "v", fileName = "m.gguf", sha256 = "ab")
        assertNull(InstalledModelMetadata.read(dir)!!.minConfidence)
    }
}
