package dev.heyari.ari.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request/response shaping, which is where an OpenAI-compatible client
 * actually goes wrong: a URL the user pasted in one of three forms, a body some
 * servers answer as bare text, and a status that has to become the right
 * sentence in front of the user.
 */
class CloudTranscriberTest {

    // --- URL resolution: people paste all three of these ---

    @Test fun openai_style_base_gets_the_path_appended() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            CloudTranscriber.transcriptionUrl("https://api.openai.com/v1"),
        )
    }

    @Test fun full_path_is_left_alone() {
        assertEquals(
            "http://homeassistant.local:10300/v1/audio/transcriptions",
            CloudTranscriber.transcriptionUrl("http://homeassistant.local:10300/v1/audio/transcriptions"),
        )
    }

    @Test fun trailing_slash_and_whitespace_are_tolerated() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            CloudTranscriber.transcriptionUrl("  https://api.openai.com/v1/  "),
        )
    }

    // --- Response parsing ---

    @Test fun json_body_yields_text_field() {
        assertEquals("turn on the kitchen light", CloudTranscriber.parseTranscript("""{"text":"turn on the kitchen light"}"""))
    }

    @Test fun json_text_is_trimmed() {
        assertEquals("hello", CloudTranscriber.parseTranscript("""{"text":"  hello  "}"""))
    }

    @Test fun plain_text_body_is_accepted() {
        // Some self-hosted whisper builds answer with the bare transcript.
        assertEquals("how's the weather", CloudTranscriber.parseTranscript("how's the weather\n"))
    }

    @Test fun json_without_text_field_is_blank() {
        assertEquals("", CloudTranscriber.parseTranscript("""{"error":"nope"}"""))
    }

    @Test fun malformed_json_is_blank_not_a_crash() {
        assertEquals("", CloudTranscriber.parseTranscript("""{"text": """))
    }

    // --- Status mapping ---

    @Test fun unauthorized_and_forbidden_are_auth_failures() {
        assertEquals(CloudSttFailure.AUTH, CloudTranscriber.failureFor(401))
        assertEquals(CloudSttFailure.AUTH, CloudTranscriber.failureFor(403))
    }

    @Test fun other_errors_are_server_failures() {
        assertEquals(CloudSttFailure.SERVER, CloudTranscriber.failureFor(500))
        assertEquals(CloudSttFailure.SERVER, CloudTranscriber.failureFor(429))
        assertEquals(CloudSttFailure.SERVER, CloudTranscriber.failureFor(404))
    }

    // --- Multipart body ---

    @Test fun multipart_carries_model_language_and_wav() {
        val wav = byteArrayOf(0x52, 0x49, 0x46, 0x46) // "RIFF"
        val body = CloudTranscriber.multipartBody(wav, "whisper-1", "en", "BOUND")
            .toString(Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"model\"\r\n\r\nwhisper-1\r\n"))
        assertTrue(body.contains("name=\"language\"\r\n\r\nen\r\n"))
        assertTrue(body.contains("filename=\"audio.wav\""))
        assertTrue(body.contains("Content-Type: audio/wav"))
        assertTrue(body.contains("RIFF"))
        assertTrue(body.endsWith("\r\n--BOUND--\r\n"))
    }

    @Test fun regional_locale_is_narrowed_to_iso_639_1() {
        // The API rejects "en-GB"; it wants "en".
        val body = CloudTranscriber.multipartBody(ByteArray(0), "whisper-1", "en-GB", "B")
            .toString(Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"language\"\r\n\r\nen\r\n"))
    }

    @Test fun uppercase_locale_is_lowercased() {
        val body = CloudTranscriber.multipartBody(ByteArray(0), "whisper-1", "IT", "B")
            .toString(Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"language\"\r\n\r\nit\r\n"))
    }
}
