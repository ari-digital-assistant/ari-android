package dev.heyari.ari.stt

/**
 * Metadata for a downloadable STT model.
 *
 * Models are downloaded into [filesDir]/models/<id>/ on demand. Encoder/decoder/joiner
 * filenames are kept distinct so int8 and fp32 variants of the same family can coexist.
 *
 * `joinerFile` is nullable: streaming Zipformer-transducer models (Kroko, Nemotron) have
 * a separate joiner network, while encoder-decoder models (Whisper) do not.
 */
data class SttModel(
    val id: String,
    val displayName: String,
    val description: String,
    val totalBytes: Long,
    val encoderFile: String,
    val decoderFile: String,
    val joinerFile: String?,
    val tokensFile: String = "tokens.txt",
    val baseUrl: String,
    val modelType: String = "zipformer",
    /**
     * Bundle manifest endpoint covering all component files. Empty
     * means "no manifest published — fall back to per-file legacy URLs
     * derived from [baseUrl] with SHA verification skipped and sidecar
     * version=unknown".
     */
    val manifestUrl: String = "",
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
        manifestUrl = "https://github.com/ari-digital-assistant/ari-tools/releases/download/stt-kroko-latest/manifest.json",
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
        manifestUrl = "https://github.com/ari-digital-assistant/ari-tools/releases/download/stt-nemotron-latest/manifest.json",
    )

    /**
     * OpenAI's whisper-large-v3-turbo, exported to ONNX and int8-quantised
     * by csukuangfj. Multilingual (99 languages) — the only on-device option
     * for non-English speech today.
     *
     * Encoder-decoder architecture, no joiner. Non-streaming: the full
     * utterance is buffered and decoded in one shot at end-of-speech, so
     * there are no partials and the transcript appears all at once.
     * VAD-based endpointing is used in [SpeechRecognizer.startListening]
     * for this model type — sherpa's online-stream endpoint detection
     * doesn't apply to offline recognizers.
     */
    val WHISPER_TURBO = SttModel(
        id = "whisper-turbo-int8-2024-09",
        displayName = "Multilingual (Whisper Turbo int8)",
        description = "99 languages. ~1 GB. Non-streaming.",
        totalBytes = 1_037_000_000L,
        encoderFile = "turbo-encoder.int8.onnx",
        decoderFile = "turbo-decoder.int8.onnx",
        joinerFile = null,
        tokensFile = "turbo-tokens.txt",
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main",
        modelType = "whisper",
        manifestUrl = "https://github.com/ari-digital-assistant/ari-tools/releases/download/stt-whisper-turbo-latest/manifest.json",
    )

    val all = listOf(KROKO, NEMOTRON, WHISPER_TURBO)

    fun byId(id: String?): SttModel? = all.firstOrNull { it.id == id }
}
