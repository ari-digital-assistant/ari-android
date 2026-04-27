package dev.heyari.ari.llm

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
    val displayName: String,
    val description: String,
    val totalBytes: Long,
    val fileName: String,
    val downloadUrl: String,
    val size: LlmSize,
)

object LlmModelRegistry {
    val NONE_ID = "none"

    val SMALL = LlmModel(
        id = "gemma3-1b-q4",
        displayName = "Small (Gemma 3 1B)",
        description = "Fast and efficient (~769 MB download, 4 GB+ RAM). Used exclusively for skill routing.",
        totalBytes = 806_058_272L,
        fileName = "gemma-3-1b-it-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
        size = LlmSize.Small,
    )

    val MEDIUM = LlmModel(
        id = "gemma4-e2b-q4",
        displayName = "Medium (Gemma 4 E2B)",
        description = "A good balance of performance and capability (~3.1 GB download, 6 GB+ RAM). Suitable for a wide range of tasks.",
        totalBytes = 3_110_000_000L,
        fileName = "gemma-4-E2B-it-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf",
        size = LlmSize.Medium,
    )

    val LARGE = LlmModel(
        id = "gemma4-e4b-q4",
        displayName = "Large (Gemma 4 E4B)",
        description = "Most capable (~5 GB download, 8 GB+ RAM). Best for complex tasks and understanding nuanced language, but may be slower and require more resources.",
        totalBytes = 4_980_000_000L,
        fileName = "gemma-4-E4B-it-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_K_M.gguf",
        size = LlmSize.Large,
    )

    val all = listOf(SMALL, MEDIUM, LARGE)

    fun byId(id: String?): LlmModel? = all.firstOrNull { it.id == id }
}
