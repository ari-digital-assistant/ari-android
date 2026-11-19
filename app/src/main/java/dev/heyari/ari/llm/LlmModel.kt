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
 * Metadata for a downloadable on-device LLM model.
 *
 * Models are downloaded into [filesDir]/models/llm/<id>/ on demand. Each
 * tier is a single `.litertlm` or `.task` file, which the engine runs on
 * LiteRT-LM (ari-android#52). LiteRT-LM keeps a repacked copy of the weights beside it, about as big again, which
 * goes when the tier's directory is deleted.
 */
data class LlmModel(
    val id: String,
    @param:StringRes val displayNameRes: Int,
    val totalBytes: Long,
    /**
     * What the tier takes on the phone once it has loaded: the file plus the
     * weight cache LiteRT-LM writes beside it on the CPU, as measured.
     */
    val installedBytes: Long,
    /**
     * Peak memory while answering, as measured: the weights, which it reads
     * in full for every word, plus its working memory. [PhoneMemory] grades
     * the model against the phone with this.
     */
    val runtimeBytes: Long,
    val fileName: String,
    val downloadUrl: String,
    val size: LlmSize,
    /**
     * Out of 5, against the other tiers: how quickly it answers and how many
     * answers it gets right. The settings screens draw them as bars.
     */
    val speed: Int,
    val accuracy: Int,
    /**
     * Manifest endpoint for this tier. Empty string means "no manifest
     * published yet — fall back to [downloadUrl] with no SHA verify and
     * sidecar version=unknown". Auto-update polls this URL on its 24h
     * cadence.
     */
    val manifestUrl: String = "",
    /**
     * The GGUF an earlier version of Ari downloaded for this tier. Ari can't
     * run it since llama.cpp went, but it keeps the tier counted as
     * installed, so the update check offers the tier's own file, and it goes
     * when that file arrives (ari-android#52). Null for a tier that never
     * had one.
     */
    val legacyFileName: String? = null,
)

/**
 * [LlmModel.runtimeBytes] figures come from ari-android#52 (2026-11-19): each
 * model loaded by LiteRT-LM 0.19.1 on the CPU (2,048-token context) on a Pixel
 * 10 Pro Fold, answering three questions, with the process's peak resident
 * memory read from /proc, the larger of a first load (which writes the weight
 * cache) and a second. Measure again when the model, its quantisation, the
 * runtime or the context size changes. [LlmModel.installedBytes] is the same
 * runs' files: each model plus its `.xnnpack_cache`.
 *
 * [LlmModel.speed] and [LlmModel.accuracy] come from the same phone and the
 * 38 questions of ari-android#32, blind-graded: median answer 1.33 s, 1.51 s
 * and 4.27 s, and 31, 35 and 38 right, for Small, Medium and Large.
 *
 * The ids are stored in settings, so they keep the names they had when every
 * tier was a Q4 GGUF. The manifests are separate from the GGUF ones
 * (`llm-<tier>-latest`), which older versions of Ari still read and would
 * save under the GGUF's name.
 */
object LlmModelRegistry {
    val NONE_ID = "none"

    val SMALL = LlmModel(
        id = "gemma3-1b-q4",
        displayNameRes = R.string.model_llm_small_name,
        totalBytes = 1_073_765_694L,
        installedBytes = 2_082_181_310L,
        runtimeBytes = 1_745_000_000L,
        // The 8-bit build: Google's 4-bit .litertlm of the same model got 20
        // of 38 answers right against this one's 31 (ari-android#52).
        fileName = "Gemma3-1B-IT_multi-prefill-seq_q8_ekv2048.task",
        // Hugging Face holds it behind Gemma's licence prompt, so the
        // fallback is Ari's mirror of the same bytes.
        downloadUrl = "https://heyari.dev/models/llm/e79a6c3fc0fd109f3b2697e7ae0905fcfded68640656d0433ca6276dc8d1b734/Gemma3-1B-IT_multi-prefill-seq_q8_ekv2048.task",
        size = LlmSize.Small,
        speed = 5,
        accuracy = 3,
        manifestUrl = "https://github.com/ari-digital-assistant/ari-tools/releases/download/llm-small-litert-latest/manifest.json",
        legacyFileName = "gemma-3-1b-it-Q4_K_M.gguf",
    )

    val MEDIUM = LlmModel(
        id = "gemma4-e2b-q4",
        displayNameRes = R.string.model_llm_medium_name,
        totalBytes = 2_588_147_712L,
        installedBytes = 3_376_560_448L,
        runtimeBytes = 1_531_000_000L,
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        size = LlmSize.Medium,
        speed = 4,
        accuracy = 4,
        manifestUrl = "https://github.com/ari-digital-assistant/ari-tools/releases/download/llm-medium-litert-latest/manifest.json",
        legacyFileName = "gemma-4-E2B-it-Q4_K_M.gguf",
    )

    val LARGE = LlmModel(
        id = "gemma4-e4b-q4",
        displayNameRes = R.string.model_llm_large_name,
        totalBytes = 3_659_530_240L,
        installedBytes = 5_869_864_656L,
        runtimeBytes = 3_145_000_000L,
        fileName = "gemma-4-E4B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        size = LlmSize.Large,
        speed = 2,
        accuracy = 5,
        manifestUrl = "https://github.com/ari-digital-assistant/ari-tools/releases/download/llm-large-litert-latest/manifest.json",
        legacyFileName = "gemma-4-E4B-it-Q4_K_M.gguf",
    )

    val all = listOf(SMALL, MEDIUM, LARGE)

    fun byId(id: String?): LlmModel? = all.firstOrNull { it.id == id }
}
