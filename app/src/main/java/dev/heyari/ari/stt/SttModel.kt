package dev.heyari.ari.stt

/**
 * How the user wants their speech transcribed. This is the only STT choice we
 * put in front of them — which *model* serves [ON_DEVICE] is decided by their
 * locale (see [SttModelRegistry.onDeviceFor]), because the answer is forced and
 * asking would only expose architecture names nobody should have to care about.
 */
enum class SttMode {
    /** Local model. Private, works offline, smaller vocabulary. */
    ON_DEVICE,

    /**
     * OpenAI's hosted transcription. Needs only an API key — the endpoint and
     * model are ours to pick, and picking them is the point of having a preset.
     */
    OPENAI,

    /**
     * Any other OpenAI-compatible `/audio/transcriptions` endpoint: whisper.cpp,
     * faster-whisper, Home Assistant's Whisper add-on. Needs a URL; a key only
     * if the server asks for one.
     */
    SELF_HOSTED,
    ;

    val slug: String get() = name.lowercase()

    /** True for anything that transcribes off the device. */
    val isCloud: Boolean get() = this != ON_DEVICE

    companion object {
        /**
         * Unknown slugs fall back to [ON_DEVICE] — including the short-lived
         * `cloud` value this enum replaced, which was only ever selectable for
         * the few minutes between the picker landing and this split. Falling
         * back to on-device is the safe direction: it costs a re-pick, not a
         * request to an endpoint the user did not choose.
         */
        fun fromSlug(slug: String?): SttMode =
            entries.firstOrNull { it.slug == slug } ?: ON_DEVICE
    }
}

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

    val all = listOf(KROKO, WHISPER_TURBO)

    fun byId(id: String?): SttModel? = all.firstOrNull { it.id == id }

    /**
     * The on-device model for [locale]. Users choose between on-device and
     * cloud, not between architectures — the language decides which local
     * model can actually serve them, so picking it is our job, not theirs.
     *
     * [KROKO] is English-only (the `-en-` in its upstream repo name is
     * literal), so every other locale gets [WHISPER_TURBO], the only
     * multilingual on-device option. That costs non-English users ~1 GB
     * instead of 71 MB, which is the price of not forcing them to the cloud.
     */
    fun onDeviceFor(locale: String): SttModel =
        if (locale.startsWith("en")) KROKO else WHISPER_TURBO

    /**
     * Model ids this build no longer ships. An install whose active id is in
     * here has to be migrated — [byId] returns null for it, which would leave
     * the user with no recogniser at all rather than a degraded one.
     *
     * `nemotron-0.6b-int8-2026-01-14` was dropped after replaying the debug
     * capture set through sherpa offline: given identical audio it produced
     * "how's the weat" and "ton" where the 71 MB Kroko produced "how's the
     * weather" and "turn on the kitchen table". Ten times the size, an order
     * of magnitude worse — most likely int8 quantisation damage to a 0.6B
     * streaming transducer.
     */
    val retiredIds = setOf("nemotron-0.6b-int8-2026-01-14")
}
