package dev.heyari.ari.models

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Per-model `version.json` sidecar living next to the model file(s).
 *
 * For single-file models (router, LLM tier), the sidecar lives in the
 * same directory as the GGUF and records `{ version, sha256 }`. For
 * multi-file bundles (STT), it records the bundle version plus a
 * per-file sha256 list so callers can verify any individual file
 * survived disk corruption.
 *
 * Reads on existing installs without a sidecar return [UNKNOWN_VERSION],
 * which the auto-update checker treats as "always stale" so the next
 * scheduled check offers a one-time re-download to bring the user onto
 * the manifest-tracked path. This is a deliberate, observable migration
 * step — silent best-effort version inference would be worse.
 */
object InstalledModelMetadata {
    const val SIDECAR_FILENAME = "version.json"
    const val UNKNOWN_VERSION = "unknown"
    private const val TAG = "InstalledModelMetadata"

    /** Read the sidecar in [modelDir]. Returns null if no sidecar found. */
    fun read(modelDir: File): InstalledVersion? {
        val sidecar = File(modelDir, SIDECAR_FILENAME)
        if (!sidecar.isFile) return null
        return try {
            val obj = JSONObject(sidecar.readText())
            val version = obj.getString("version")
            val files = if (obj.has("files")) {
                val arr = obj.getJSONArray("files")
                List(arr.length()) { i ->
                    val f = arr.getJSONObject(i)
                    InstalledFile(name = f.getString("name"), sha256 = f.getString("sha256"))
                }
            } else {
                listOf(InstalledFile(name = obj.optString("name", ""), sha256 = obj.getString("sha256")))
            }
            val minConfidence =
                if (obj.has("min_confidence")) obj.getDouble("min_confidence").toFloat() else null
            InstalledVersion(version, files, minConfidence)
        } catch (e: Exception) {
            Log.w(TAG, "failed to parse sidecar at ${sidecar.absolutePath}", e)
            null
        }
    }

    /** Read just the version string; treats missing/corrupt as [UNKNOWN_VERSION]. */
    fun readVersion(modelDir: File): String = read(modelDir)?.version ?: UNKNOWN_VERSION

    /**
     * Write a single-file sidecar (router, LLM tier). [minConfidence] is the
     * router manifest's per-model confidence floor; it travels in the sidecar
     * because the floor belongs to the FILE on disk — reading it from
     * whatever manifest is current at engine-load time would apply a newer
     * model's floor to an older model.
     */
    fun writeSingle(
        modelDir: File,
        version: String,
        fileName: String,
        sha256: String,
        minConfidence: Float? = null,
    ) {
        val obj = JSONObject().apply {
            put("version", version)
            put("name", fileName)
            put("sha256", sha256)
            if (minConfidence != null) put("min_confidence", minConfidence.toDouble())
        }
        File(modelDir, SIDECAR_FILENAME).writeText(obj.toString())
    }

    /** Write a multi-file bundle sidecar (STT). */
    fun writeBundle(modelDir: File, version: String, files: List<InstalledFile>) {
        val arr = JSONArray()
        for (f in files) {
            arr.put(JSONObject().put("name", f.name).put("sha256", f.sha256))
        }
        val obj = JSONObject().apply {
            put("version", version)
            put("files", arr)
        }
        File(modelDir, SIDECAR_FILENAME).writeText(obj.toString())
    }

    /**
     * Hex-lowercase SHA-256 of [file]. Streams in 64 KiB chunks so multi-GB
     * GGUFs don't blow the heap. Throws on I/O error — callers should
     * treat any throw as "verification failed" and delete the file.
     */
    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

data class InstalledVersion(
    val version: String,
    val files: List<InstalledFile>,
    /** Per-model router floor from the manifest that installed this model; null = compiled default. */
    val minConfidence: Float? = null,
)

data class InstalledFile(val name: String, val sha256: String)
