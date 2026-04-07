package dev.heyari.ari.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class SpeechOutput(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context, this)
    private val utteranceId = AtomicInteger(0)
    private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ready) {
                Log.e(TAG, "Language not supported: $result")
            } else {
                Log.i(TAG, "TTS initialised")
            }
        } else {
            Log.e(TAG, "TTS init failed: $status")
        }
    }

    fun speak(text: String) {
        if (!ready) {
            Log.w(TAG, "TTS not ready, dropping: $text")
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "ari-${utteranceId.incrementAndGet()}")
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        Log.i(TAG, "TTS shut down")
    }

    companion object {
        private const val TAG = "SpeechOutput"
    }
}
