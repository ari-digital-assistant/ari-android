package dev.heyari.ari.llm

/**
 * Metadata for a downloadable on-device LLM model (GGUF format).
 *
 * Models are downloaded into [filesDir]/models/llm/<id>/ on demand.
 * Each tier is a single GGUF file — no multi-file layout like STT.
 */
data class LlmModel(
    val id: String,
    val displayName: String,
    val description: String,
    val totalBytes: Long,
    val fileName: String,
    val downloadUrl: String,
)

object LlmModelRegistry {
    val NONE_ID = "none"

    val SMALL = LlmModel(
        id = "gemma3-1b-q4",
        displayName = "Small (Gemma 3 1B)",
        description = "Fast, lightweight. ~769 MB. Good for skill rerouting and simple questions.",
        totalBytes = 806_058_272L,
        fileName = "gemma-3-1b-it-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
    )

    val MEDIUM = LlmModel(
        id = "gemma4-e2b-q4",
        displayName = "Medium (Gemma 4 E2B)",
        description = "Balanced. ~3.1 GB. Better answers, needs 6 GB+ RAM.",
        totalBytes = 3_110_000_000L,
        fileName = "gemma-4-E2B-it-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf",
    )

    val LARGE = LlmModel(
        id = "gemma4-e4b-q4",
        displayName = "Large (Gemma 4 E4B)",
        description = "Best quality. ~5 GB. Needs 8 GB+ RAM. Flagship phones only.",
        totalBytes = 4_980_000_000L,
        fileName = "gemma-4-E4B-it-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_K_M.gguf",
    )

    val all = listOf(SMALL, MEDIUM, LARGE)

    fun byId(id: String?): LlmModel? = all.firstOrNull { it.id == id }
}
