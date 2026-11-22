package dev.heyari.ari.stt

import dev.heyari.ari.audio.wavBytes
import dev.heyari.ari.data.SecretStore
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.FfiCloudSttService
import uniffi.ari_ffi.cloudTranscribe
import uniffi.ari_ffi.cloudTranscribeWakeClip

/**
 * Speech-to-text against any OpenAI-compatible `/audio/transcriptions`
 * endpoint: OpenAI itself, or a self-hosted Whisper server.
 *
 * The request is the engine's (ari-voice `cloud_stt`). This is the app's side
 * of it: which service the user picked, where, and their key.
 */
@Singleton
class CloudTranscriber @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
) {
    /**
     * Transcribe [pcm] (16-bit mono 16 kHz) and return the text.
     *
     * @throws uniffi.ari_ffi.FfiCloudSttException naming why it failed, so the
     *   caller can turn it into a message. Never returns blank: an empty
     *   transcript is [uniffi.ari_ffi.FfiCloudSttException.Empty].
     */
    suspend fun transcribe(pcm: ShortArray, locale: String): String = withContext(Dispatchers.IO) {
        cloudTranscribe(service(), secretStore.get(SECRET_SCOPE, SECRET_KEY), wavBytes(pcm), locale)
    }

    /**
     * [transcribe], for the short clip a quiet wake checks for its name. Null
     * where the engine does not run the early check (a self-hosted server).
     */
    suspend fun transcribeWakeClip(pcm: ShortArray, locale: String): String? = withContext(Dispatchers.IO) {
        cloudTranscribeWakeClip(service(), secretStore.get(SECRET_SCOPE, SECRET_KEY), wavBytes(pcm), locale)
    }

    private suspend fun service(): FfiCloudSttService = when (settingsRepository.sttMode.first()) {
        SttMode.OPENAI -> FfiCloudSttService.OpenAi
        else -> FfiCloudSttService.SelfHosted(
            endpoint = settingsRepository.cloudSttEndpoint.first(),
            model = settingsRepository.cloudSttModel.first(),
        )
    }

    companion object {
        /** SecretStore namespace — not a real skill id, but the same store. */
        const val SECRET_SCOPE = "stt.cloud"
        const val SECRET_KEY = "api_key"

        /** Starting point for [SttMode.SELF_HOSTED], which the user then edits. */
        const val DEFAULT_SELF_HOSTED_ENDPOINT = "http://homeassistant.local:10300/v1"

        /**
         * Default model name for a self-hosted server. Most OpenAI-compatible
         * Whisper servers ignore this field or expect the legacy name, so
         * `whisper-1` is the safer default here — the opposite of the hosted
         * case.
         */
        const val DEFAULT_SELF_HOSTED_MODEL = "whisper-1"
    }
}
