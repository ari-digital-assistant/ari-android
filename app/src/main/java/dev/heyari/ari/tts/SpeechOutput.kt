package dev.heyari.ari.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class SpeechOutput(
    context: Context,
    private val settingsRepository: SettingsRepository,
) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context, this)
    private val utteranceId = AtomicInteger(0)
    private var ready = false

    @Volatile
    private var activeVoiceName: String? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Read the saved voice preference here (off the main thread --
            // onInit is called on a binder thread by the TTS engine).
            activeVoiceName = runBlocking { settingsRepository.activeTtsVoice.first() }
            applyVoice()
            ready = true
            Log.i(TAG, "TTS initialised")
        } else {
            Log.e(TAG, "TTS init failed: $status")
        }
    }

    fun getAvailableVoices(): List<Voice> = tts.voices?.toList() ?: emptyList()

    fun setVoice(voiceName: String?) {
        activeVoiceName = voiceName
        if (ready) applyVoice()
    }

    fun preview(voiceName: String) {
        if (!ready) return
        tts.stop()

        val voice = tts.voices?.find { it.name == voiceName } ?: return
        tts.voice = voice

        val id = "ari-preview-${utteranceId.incrementAndGet()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) applyVoice()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) applyVoice()
            }
        })
        tts.speak("Hello, I'm Ari", TextToSpeech.QUEUE_FLUSH, null, id)
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

    private fun applyVoice() {
        val name = activeVoiceName
        if (name != null) {
            val voice = tts.voices?.find { it.name == name }
            if (voice != null) {
                tts.voice = voice
                Log.i(TAG, "Voice set: $name")
                return
            }
            Log.w(TAG, "Saved voice '$name' not found, falling back to system default")
        }
        val result = tts.setLanguage(Locale.US)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "Language not supported: $result")
        }
    }

    companion object {
        private const val TAG = "SpeechOutput"
    }
}
