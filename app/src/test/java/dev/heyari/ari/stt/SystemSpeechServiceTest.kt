package dev.heyari.ari.stt

import android.speech.SpeechRecognizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SystemSpeechServiceTest {

    @Test
    fun `hearing nothing is no speech, not a failure`() {
        assertEquals(ServiceFailure.NO_SPEECH, serviceFailure(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(ServiceFailure.NO_SPEECH, serviceFailure(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    }

    @Test
    fun `a service turning the audio down is a refusal`() {
        // What Google's services answered handed-over audio with on the spike (#28).
        assertEquals(ServiceFailure.REFUSED, serviceFailure(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(ServiceFailure.REFUSED, serviceFailure(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertEquals(ServiceFailure.REFUSED, serviceFailure(SpeechRecognizer.ERROR_CLIENT))
    }

    @Test
    fun `anything else is a plain failure`() {
        assertEquals(ServiceFailure.FAILED, serviceFailure(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(ServiceFailure.FAILED, serviceFailure(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
    }

    @Test
    fun `the phone's regional tag is used when it is in Ari's language`() {
        assertEquals("en-GB", serviceLanguageTag("en", Locale.UK))
        assertEquals("it", serviceLanguageTag("it", Locale.UK))
    }

    @Test
    fun `samples go out little-endian`() {
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x80.toByte()),
            pcmBytes(shortArrayOf(0x1234, -1, Short.MIN_VALUE)),
        )
    }
}
