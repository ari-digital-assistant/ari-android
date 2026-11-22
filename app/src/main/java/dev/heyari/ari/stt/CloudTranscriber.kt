package dev.heyari.ari.stt

import dev.heyari.ari.data.SecretStore
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.FfiCloudSttService
import uniffi.ari_ffi.FfiCloudSttSetup

/**
 * Which OpenAI-compatible `/audio/transcriptions` endpoint the user picked,
 * OpenAI itself or a self-hosted Whisper server, and their key. The requests
 * are the engine's (ari-voice `cloud_stt`); it asks for this at each one, so a
 * settings change needs nothing pushed.
 */
@Singleton
class CloudTranscriber @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
) {
    /** The service and key now. Blocking: the engine asks from its own threads. */
    fun setup(): FfiCloudSttSetup = runBlocking {
        FfiCloudSttSetup(service = service(), apiKey = secretStore.get(SECRET_SCOPE, SECRET_KEY))
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
