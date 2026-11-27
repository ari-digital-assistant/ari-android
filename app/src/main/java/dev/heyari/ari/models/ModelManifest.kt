package dev.heyari.ari.models

import uniffi.ari_ffi.parseModelManifest

/**
 * Versioned manifest published alongside an on-device model: a version
 * covering a list of files, each with its checksum. Reading one is the
 * engine's (`ari_core::model_manifest`), which accepts both the single-file
 * shape (router, LLM) and the bundle (STT).
 */
data class ModelManifest(
    val version: String,
    val releasedAt: String?,
    val files: List<ManifestFile>,
) {
    val totalSizeBytes: Long get() = files.sumOf { it.sizeBytes }

    companion object {
        fun parse(json: String): ModelManifest = parseModelManifest(json).let { manifest ->
            ModelManifest(
                version = manifest.version,
                releasedAt = manifest.releasedAt,
                files = manifest.files.map { ManifestFile(it.name, it.url, it.sha256, it.sizeBytes) },
            )
        }
    }
}

data class ManifestFile(
    val name: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)
