package dev.heyari.ari.stt

import androidx.annotation.StringRes
import dev.heyari.ari.R
import uniffi.ari_ffi.FfiSttModel
import uniffi.ari_ffi.sttModels
import uniffi.ari_ffi.sttOnDeviceChoices
import uniffi.ari_ffi.sttOnDeviceFor
import uniffi.ari_ffi.sttRetiredIds

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

    /**
     * A speech recognition app installed on the phone (Marmalade STT and the
     * like), handed Ari's own audio through Android's `SpeechRecognizer`. Only
     * an app that accepts handed-over audio works; Google's don't
     * (ari-android#28).
     */
    SYSTEM_SERVICE,
    ;

    val slug: String get() = name.lowercase()

    /**
     * True for the modes where Ari sends audio to a server itself. A system
     * service may or may not, but that is the service's business, not Ari's.
     */
    val isCloud: Boolean get() = this == OPENAI || this == SELF_HOSTED

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
 * A downloadable on-device STT model: the engine's catalog entry
 * (`ari_voice::stt_models`) with the names the app shows for it.
 *
 * Models are downloaded into [filesDir]/models/<id>/ on demand. `joinerFile`
 * is null for encoder-decoder models (Whisper); transducers (Kroko, Parakeet)
 * have one.
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
    val tokensFile: String,
    val baseUrl: String,
    val modelType: String,
    /** Bundle manifest endpoint covering all component files. */
    val manifestUrl: String,
) {
    companion object {
        fun from(model: FfiSttModel): SttModel = SttModel(
            id = model.id,
            displayNameRes = requireNotNull(SttModelNames.displayNameRes(model.id)) {
                "the engine offers ${model.id}, which the app has no name for"
            },
            descriptionRes = requireNotNull(SttModelNames.descriptionRes(model.id)),
            totalBytes = model.totalBytes,
            encoderFile = model.encoderFile,
            decoderFile = model.decoderFile,
            joinerFile = model.joinerFile,
            tokensFile = model.tokensFile,
            baseUrl = model.baseUrl,
            modelType = model.modelType,
            manifestUrl = model.manifestUrl,
        )
    }
}

/**
 * What the app calls each model the engine offers. The names are the app's
 * words; which models there are, and which serves which language, is the
 * engine's (`ari_voice::stt_models`).
 */
object SttModelNames {
    private val names = mapOf(
        "kroko-2025-08-06" to (R.string.model_stt_kroko_name to R.string.model_stt_kroko_desc),
        "parakeet-ultra-int8-2026-11" to
            (R.string.model_stt_parakeet_ultra_name to R.string.model_stt_parakeet_ultra_desc),
        "whisper-turbo-int8-2024-09" to
            (R.string.model_stt_whisper_turbo_name to R.string.model_stt_whisper_turbo_desc),
    )

    /** Null for a model this build doesn't offer, a retired one among them. */
    @StringRes fun displayNameRes(id: String): Int? = names[id]?.first

    @StringRes fun descriptionRes(id: String): Int? = names[id]?.second
}

/**
 * The on-device models, read from the engine: which there are, which can
 * transcribe a language, and which are retired.
 */
object SttModelRegistry {
    val all: List<SttModel> by lazy { sttModels().map(SttModel::from) }

    fun byId(id: String?): SttModel? = all.firstOrNull { it.id == id }

    /**
     * The on-device models that can transcribe [locale], recommended first.
     * Users choose between on-device and cloud; the language decides which
     * local models can serve them, and the first-run wizard only ever offers
     * the first. Settings shows them all.
     */
    fun onDeviceChoicesFor(locale: String): List<SttModel> =
        sttOnDeviceChoices(locale).map(SttModel::from)

    /** The recommended on-device model for [locale]: see [onDeviceChoicesFor]. */
    fun onDeviceFor(locale: String): SttModel = SttModel.from(sttOnDeviceFor(locale, null))

    /**
     * [current] if it can transcribe [locale], else the recommended model: a
     * choice made in settings outlives a trip to the cloud and back.
     */
    fun onDeviceFor(locale: String, current: SttModel?): SttModel =
        SttModel.from(sttOnDeviceFor(locale, current?.id))

    /**
     * Model ids this build no longer ships. An install whose active id is in
     * here has to be migrated: [byId] returns null for it, which would leave
     * the user with no recogniser at all rather than a degraded one.
     */
    val retiredIds: Set<String> by lazy { sttRetiredIds().toSet() }
}
