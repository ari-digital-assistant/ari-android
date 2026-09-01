package dev.heyari.ari.stt

import android.util.Log
import dev.heyari.ari.audio.wavBytes
import dev.heyari.ari.data.SecretStore
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Where a cloud transcription attempt went wrong, so the host can say why. */
enum class CloudSttFailure { NOT_CONFIGURED, NETWORK, AUTH, SERVER, EMPTY }

class CloudSttException(val failure: CloudSttFailure, message: String) : Exception(message)

/**
 * Speech-to-text against any OpenAI-compatible `/audio/transcriptions`
 * endpoint.
 *
 * Deliberately not "the OpenAI transcriber". The same request shape is served
 * by OpenAI, by Groq, and by self-hosted `whisper.cpp` / `faster-whisper` —
 * including the Whisper add-on that ships with Home Assistant's Assist stack.
 * So the endpoint is a user-supplied URL and the API key is optional: point it
 * at your own box and no key is needed, no audio leaves your network, and
 * there is no per-minute bill. That is the difference between offering a cloud
 * option and taking a dependency on one vendor.
 *
 * Uses [HttpURLConnection] because that is what the rest of the app already
 * uses (see `ModelUpdateChecker`) — a transcription
 * POST does not justify adding an HTTP client to the dependency tree.
 */
@Singleton
class CloudTranscriber @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
) {
    /**
     * Transcribe [pcm] (16-bit mono 16 kHz) and return the text.
     *
     * @throws CloudSttException with a [CloudSttFailure] the caller can turn
     *   into a message. Never returns blank — an empty transcript is [EMPTY],
     *   because "Ari heard nothing" and "the request failed" want different
     *   words in front of the user.
     */
    suspend fun transcribe(pcm: ShortArray, locale: String): String = withContext(Dispatchers.IO) {
        val mode = settingsRepository.sttMode.first()
        // OpenAI is a preset: we own the endpoint and the model so the user only
        // has to supply a key. Self-hosted is the opposite — we know neither.
        val base = when (mode) {
            SttMode.OPENAI -> OPENAI_ENDPOINT
            else -> settingsRepository.cloudSttEndpoint.first()
        }
        if (base.isBlank()) {
            throw CloudSttException(CloudSttFailure.NOT_CONFIGURED, "no cloud STT endpoint configured")
        }
        val model = when (mode) {
            SttMode.OPENAI -> OPENAI_MODEL
            else -> settingsRepository.cloudSttModel.first()
        }
        val apiKey = secretStore.get(SECRET_SCOPE, SECRET_KEY)
        if (mode == SttMode.OPENAI && apiKey.isNullOrBlank()) {
            // Distinguish "you haven't set this up" from "your key was
            // rejected": the first is a Settings trip, the second is a new key.
            throw CloudSttException(CloudSttFailure.NOT_CONFIGURED, "no OpenAI API key set")
        }

        val boundary = "----AriBoundary${pcm.size}"
        val body = multipartBody(wavBytes(pcm), model, locale, boundary)

        val conn = (URL(transcriptionUrl(base)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            // Only when the user gave us one: a self-hosted endpoint has no
            // key, and sending an empty Authorization header makes some
            // servers reject the request outright.
            if (!apiKey.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            if (status !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(300)
                Log.w(TAG, "cloud STT HTTP $status: $detail")
                throw CloudSttException(failureFor(status), "HTTP $status")
            }
            val text = parseTranscript(conn.inputStream.bufferedReader().use { it.readText() })
            if (text.isBlank()) throw CloudSttException(CloudSttFailure.EMPTY, "empty transcript")
            text
        } catch (e: CloudSttException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cloud STT request failed", e)
            throw CloudSttException(CloudSttFailure.NETWORK, e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "CloudTranscriber"
        private const val CONNECT_TIMEOUT_MS = 10_000
        // Generous: a self-hosted large-v3 on modest hardware is slower than a
        // hosted API, and a timeout here costs the user their whole utterance.
        private const val READ_TIMEOUT_MS = 60_000

        /** SecretStore namespace — not a real skill id, but the same store. */
        const val SECRET_SCOPE = "stt.cloud"
        const val SECRET_KEY = "api_key"

        /** Fixed for [SttMode.OPENAI] — not user-editable, that's the preset. */
        const val OPENAI_ENDPOINT = "https://api.openai.com/v1"

        /**
         * OpenAI's recommended transcription model (verified against their API
         * docs, 2026-08). NOT `whisper-1`: that is now labelled legacy and
         * retained mainly for word/segment timestamps and English translation,
         * and it has a worse word error rate. Shipping the legacy model as the
         * "more accurate than on-device" option would defeat the purpose.
         */
        const val OPENAI_MODEL = "gpt-transcribe"

        /** Starting point for [SttMode.SELF_HOSTED], which the user then edits. */
        const val DEFAULT_SELF_HOSTED_ENDPOINT = "http://homeassistant.local:10300/v1"

        /**
         * Default model name for a self-hosted server. Most OpenAI-compatible
         * Whisper servers ignore this field or expect the legacy name, so
         * `whisper-1` is the safer default here — the opposite of the hosted
         * case.
         */
        const val DEFAULT_SELF_HOSTED_MODEL = "whisper-1"

        /**
         * Resolve the transcription URL from whatever the user typed. Accepts a
         * bare host, an OpenAI-style `/v1` base, or the full path — people
         * paste all three, and a 404 with no explanation is a miserable way to
         * find out which one was wanted.
         */
        fun transcriptionUrl(base: String): String {
            val trimmed = base.trim().trimEnd('/')
            return if (trimmed.endsWith("/audio/transcriptions")) trimmed
            else "$trimmed/audio/transcriptions"
        }

        /** Map an HTTP status onto something we can say out loud. */
        fun failureFor(status: Int): CloudSttFailure = when {
            status == 401 || status == 403 -> CloudSttFailure.AUTH
            else -> CloudSttFailure.SERVER
        }

        /**
         * Pull the transcript out of a response. OpenAI-compatible servers
         * answer `{"text": "..."}`; a plain-text body is tolerated because some
         * self-hosted builds return exactly that.
         */
        fun parseTranscript(body: String): String {
            val trimmed = body.trim()
            if (!trimmed.startsWith("{")) return trimmed
            return runCatching { JSONObject(trimmed).optString("text") }.getOrDefault("").trim()
        }

        /**
         * Build the multipart body. [locale] is sent as `language` so the
         * server does not have to guess — Whisper's auto-detection is good but
         * it does misfire on short commands, which is all Ari ever sends.
         */
        fun multipartBody(
            wav: ByteArray,
            model: String,
            locale: String,
            boundary: String,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            fun field(name: String, value: String) {
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                out.write("$value\r\n".toByteArray())
            }
            field("model", model)
            // Two-letter code only: the API wants ISO-639-1, so "en-GB" is
            // rejected where "en" is fine.
            field("language", locale.take(2).lowercase())
            out.write("--$boundary\r\n".toByteArray())
            out.write(
                "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n".toByteArray(),
            )
            out.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
            out.write(wav)
            out.write("\r\n--$boundary--\r\n".toByteArray())
            return out.toByteArray()
        }
    }
}
