package dev.heyari.ari.models

import org.json.JSONObject

/**
 * Versioned manifest published alongside an on-device model.
 *
 * Two on-the-wire shapes are accepted by [parse]:
 *
 *   single-file (router, LLM):
 *     { "version": "...", "url": "...", "sha256": "...",
 *       "size_bytes": N, "released_at": "ISO-8601" }
 *
 *   bundle (STT):
 *     { "version": "...", "released_at": "...",
 *       "files": [{ "name": "...", "url": "...",
 *                   "sha256": "...", "size_bytes": N }, ...] }
 *
 * Both normalise to the same in-memory representation: a list of
 * [ManifestFile] entries plus a single [version] string covering them
 * all. For single-file manifests, [files] has exactly one entry whose
 * `name` is the GGUF/onnx filename (server-derived from the URL path).
 */
data class ModelManifest(
    val version: String,
    val releasedAt: String?,
    val files: List<ManifestFile>,
    /**
     * Per-model router confidence floor, derived by CI's floor sweep and
     * published as `min_confidence` (router manifests only, and only since
     * Gate v4 — absent means "use the engine's compiled default"). Carried
     * into the install sidecar so the floor travels with the model file it
     * was derived FOR, not with whatever manifest is current at load time.
     */
    val minConfidence: Float? = null,
) {
    val totalSizeBytes: Long get() = files.sumOf { it.sizeBytes }

    companion object {
        fun parse(json: String): ModelManifest {
            val root = JSONObject(json)
            val version = root.getString("version")
            val releasedAt = root.optString("released_at").takeIf { it.isNotEmpty() }
            val minConfidence =
                if (root.has("min_confidence")) root.getDouble("min_confidence").toFloat() else null
            val files = if (root.has("files")) {
                val arr = root.getJSONArray("files")
                List(arr.length()) { i ->
                    val obj = arr.getJSONObject(i)
                    ManifestFile(
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        sha256 = obj.getString("sha256"),
                        sizeBytes = obj.getLong("size_bytes"),
                    )
                }
            } else {
                val url = root.getString("url")
                listOf(
                    ManifestFile(
                        name = url.substringAfterLast('/'),
                        url = url,
                        sha256 = root.getString("sha256"),
                        sizeBytes = root.getLong("size_bytes"),
                    ),
                )
            }
            require(files.isNotEmpty()) { "manifest has no files" }
            return ModelManifest(version, releasedAt, files, minConfidence)
        }
    }
}

data class ManifestFile(
    val name: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)
