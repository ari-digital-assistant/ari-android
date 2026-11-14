package dev.heyari.ari.stt

import androidx.annotation.StringRes
import dev.heyari.ari.R

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
 * `joinerFile` is nullable: transducer models (Kroko, Parakeet) have a separate joiner
 * network, while encoder-decoder models (Whisper) do not.
 */
data class SttModel(
    val id: String,
    /**
     * What the user is shown. Named by language and size, not by architecture:
     * they chose "on device", and "Kroko Zipformer2" is not a thing anyone can
     * act on. Also appears in auto-update notifications, so it has to read as a
     * noun phrase on its own ("… updated to v2").
     */
    @param:StringRes val displayNameRes: Int,
    @param:StringRes val descriptionRes: Int,
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

/** The on-device models this build ships. */
object SttModelRegistry {
    val KROKO = SttModel(
        id = "kroko-2025-08-06",
        displayNameRes = R.string.model_stt_kroko_name,
        descriptionRes = R.string.model_stt_kroko_desc,
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
     * by csukuangfj. Multilingual (99 languages): the on-device model for the
     * languages [PARAKEET_ULTRA] does not cover.
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
        displayNameRes = R.string.model_stt_whisper_turbo_name,
        descriptionRes = R.string.model_stt_whisper_turbo_desc,
        totalBytes = 1_037_000_000L,
        encoderFile = "turbo-encoder.int8.onnx",
        decoderFile = "turbo-decoder.int8.onnx",
        joinerFile = null,
        tokensFile = "turbo-tokens.txt",
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main",
        modelType = "whisper",
        manifestUrl = "https://github.com/ari-digital-assistant/ari-tools/releases/download/stt-whisper-turbo-latest/manifest.json",
    )

    /**
     * Moondream's Parakeet Ultra, a further-trained NVIDIA Parakeet TDT 0.6B
     * v3, exported to ONNX and int8-quantised by csukuangfj. CC-BY-4.0.
     *
     * Buffered like Whisper: no partials, one decode at end of speech. On the
     * November 2026 bench (ari-android#26) it matched Whisper Turbo on Italian
     * at a ninth of the decode time, and beat Kroko on English accuracy, but
     * without Kroko's live partials, at nine times the download.
     */
    val PARAKEET_ULTRA = SttModel(
        id = "parakeet-ultra-int8-2026-11",
        displayNameRes = R.string.model_stt_parakeet_ultra_name,
        descriptionRes = R.string.model_stt_parakeet_ultra_desc,
        totalBytes = 670_600_000L,
        encoderFile = "encoder.int8.onnx",
        decoderFile = "decoder.int8.onnx",
        joinerFile = "joiner.int8.onnx",
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-ultra-int8/resolve/main",
        modelType = "nemo_transducer",
        manifestUrl = "https://github.com/ari-digital-assistant/ari-tools/releases/download/stt-parakeet-ultra-latest/manifest.json",
    )

    /** The 25 languages of Parakeet TDT v3, which Ultra is trained from. */
    private val PARAKEET_LANGUAGES = setOf(
        "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "hr", "hu", "it",
        "lt", "lv", "mt", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "uk",
    )

    val all = listOf(KROKO, PARAKEET_ULTRA, WHISPER_TURBO)

    fun byId(id: String?): SttModel? = all.firstOrNull { it.id == id }

    /**
     * The on-device models that can transcribe [locale], recommended first.
     * Users choose between on-device and cloud; the language decides which
     * local models can serve them, and the first-run wizard only ever offers
     * the first. Settings shows them all.
     *
     * [KROKO] is English-only (the `-en-` in its upstream repo name is
     * literal) and stays first for English: it is small and streams.
     * [PARAKEET_ULTRA] leads for the other languages it covers, and every
     * remaining language has only [WHISPER_TURBO].
     */
    fun onDeviceChoicesFor(locale: String): List<SttModel> {
        val language = locale.lowercase().substringBefore('-').substringBefore('_')
        return when {
            language == "en" -> listOf(KROKO, PARAKEET_ULTRA)
            language in PARAKEET_LANGUAGES -> listOf(PARAKEET_ULTRA, WHISPER_TURBO)
            else -> listOf(WHISPER_TURBO)
        }
    }

    /** The recommended on-device model for [locale]: see [onDeviceChoicesFor]. */
    fun onDeviceFor(locale: String): SttModel = onDeviceChoicesFor(locale).first()

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
