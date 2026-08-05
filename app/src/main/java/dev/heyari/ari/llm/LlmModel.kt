package dev.heyari.ari.llm

import androidx.annotation.StringRes
import dev.heyari.ari.R

/**
 * Size classification of an on-device LLM tier. The engine gates Layer C
 * consultation on this — Small is too dim for structured JSON, Medium
 * and Large are eligible. Classifying by size rather than model name
 * means the underlying model can be swapped without reworking the gate.
 */
enum class LlmSize { Small, Medium, Large }

/**
 * Metadata for a downloadable on-device LLM model (GGUF format).
 *
 * Models are downloaded into [filesDir]/models/llm/<id>/ on demand.
 * Each tier is a single GGUF file — no multi-file layout like STT.
 */
data class LlmModel(
    val id: String,
    @param:StringRes val displayNameRes: Int,
    @param:StringRes val descriptionRes: Int,
    val totalBytes: Long,
    val fileName: String,
    val downloadUrl: String,
    val size: LlmSize,
    /**
     * Manifest endpoint for this tier. Empty string means "no manifest
     * published yet — fall back to [downloadUrl] with no SHA verify and
     * sidecar version=unknown". Auto-update polls this URL on its 24h
     * cadence.
     */
    val manifestUrl: String = "",
)

object LlmModelRegistry {
    val NONE_ID = "none"

    val SMALL = LlmModel(
        id = "gemma3-1b-q4",
        displayNameRes = R.string.model_llm_small_name,
        descriptionRes = R.string.model_llm_small_desc,
        totalBytes = 806_058_272L,
        fileName = "gemma-3-1b-it-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
        size = LlmSize.Small,
        manifestUrl = "https://github.com/ari-digital-assistant/ari-tools/releases/download/llm-small-latest/manifest.json",
    )

    val MEDIUM = LlmModel(
        id = "gemma4-e2b-q4",
        displayNameRes = R.string.model_llm_medium_name,
        descriptionRes = R.string.model_llm_medium_desc,
        totalBytes = 3_110_000_000L,
        fileName = "gemma-4-E2B-it-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf",
        size = LlmSize.Medium,
        manifestUrl = "https://github.com/ari-digital-assistant/ari-tools/releases/download/llm-medium-latest/manifest.json",
    )

    val LARGE = LlmModel(
        id = "gemma4-e4b-q4",
        displayNameRes = R.string.model_llm_large_name,
        descriptionRes = R.string.model_llm_large_desc,
        totalBytes = 4_980_000_000L,
        fileName = "gemma-4-E4B-it-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_K_M.gguf",
        size = LlmSize.Large,
        manifestUrl = "https://github.com/ari-digital-assistant/ari-tools/releases/download/llm-large-latest/manifest.json",
    )

    val all = listOf(SMALL, MEDIUM, LARGE)

    fun byId(id: String?): LlmModel? = all.firstOrNull { it.id == id }
}
