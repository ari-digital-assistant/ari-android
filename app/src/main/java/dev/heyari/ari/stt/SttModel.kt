package dev.heyari.ari.stt

/**
 * Metadata for a downloadable streaming STT model.
 *
 * Models are downloaded into [filesDir]/models/<id>/ on demand. Encoder/decoder/joiner
 * filenames are kept distinct so int8 and fp32 variants of the same family can coexist.
 */
data class SttModel(
    val id: String,
    val displayName: String,
    val description: String,
    val totalBytes: Long,
    val encoderFile: String,
    val decoderFile: String,
    val joinerFile: String,
    val tokensFile: String = "tokens.txt",
    val baseUrl: String,
    val modelType: String = "zipformer",
)

object SttModelRegistry {
    val KROKO = SttModel(
        id = "kroko-2025-08-06",
        displayName = "Small (Kroko Zipformer2)",
        description = "Fast, lightweight. ~71 MB. Best for short commands.",
        totalBytes = 71_500_000L,
        encoderFile = "encoder.onnx",
        decoderFile = "decoder.onnx",
        joinerFile = "joiner.onnx",
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/resolve/main",
        modelType = "zipformer2",
    )

    val NEMOTRON = SttModel(
        id = "nemotron-0.6b-int8-2026-01-14",
        displayName = "Large (Nemotron 0.6B int8)",
        description = "High accuracy with native punctuation. ~663 MB. Slower, needs more RAM.",
        totalBytes = 663_000_000L,
        encoderFile = "encoder.int8.onnx",
        decoderFile = "decoder.int8.onnx",
        joinerFile = "joiner.int8.onnx",
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14/resolve/main",
    )

    val all = listOf(KROKO, NEMOTRON)

    fun byId(id: String?): SttModel? = all.firstOrNull { it.id == id }
}
