package dev.heyari.ari.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    /**
     * Ari's currently-active language as an ISO 639-1 lowercase code
     * (`"en"`, `"it"`, …). Drives `applyVoice` when no explicit voice
     * is saved — without this, every non-English user got the
     * hardcoded `Locale.US` and Italian text was read with an English
     * TTS voice. Updated via the locale subscription below.
     */
    @Volatile
    private var activeLocale: String = "en"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // Mirror SettingsRepository.activeLocale into our local state
        // and re-apply the voice whenever it changes — so a user
        // toggling the language in settings doesn't keep the previous
        // locale's voice rendering their new-language responses.
        scope.launch {
            settingsRepository.activeLocale.collect { code ->
                val previous = activeLocale
                activeLocale = code
                if (ready && previous != code) {
                    applyVoice()
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Read both the saved voice preference and the active
            // locale here (off the main thread — onInit is called on
            // a binder thread by the TTS engine). Locale flow's
            // collector will keep `activeLocale` fresh after this
            // initial seed.
            runBlocking {
                activeVoiceName = settingsRepository.activeTtsVoice.first()
                activeLocale = settingsRepository.activeLocale.first()
            }
            applyVoice()
            ready = true
            Log.i(TAG, "TTS initialised (locale=$activeLocale)")
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
        val targetLocale = Locale.forLanguageTag(activeLocale)
        if (name != null) {
            val voice = tts.voices?.find { it.name == name }
            if (voice != null) {
                // If the user has a saved voice, but its locale doesn't
                // match Ari's active locale (e.g. saved English voice +
                // user switched Ari to Italian), the voice would mangle
                // the response — Italian text rendered with English
                // phonemes. Fall through to the locale-default branch
                // below in that case so the engine picks a sensible
                // matching voice.
                val voiceLanguage = voice.locale.language.lowercase()
                if (voiceLanguage == activeLocale.lowercase()) {
                    tts.voice = voice
                    Log.i(TAG, "Voice set: $name")
                    return
                }
                Log.w(
                    TAG,
                    "Saved voice '$name' (locale=$voiceLanguage) doesn't match Ari locale " +
                        "($activeLocale); falling back to engine default for the locale",
                )
            } else {
                Log.w(TAG, "Saved voice '$name' not found, falling back to engine default")
            }
        }
        val result = tts.setLanguage(targetLocale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(
                TAG,
                "Language $activeLocale not supported (result=$result); falling back to en-US",
            )
            tts.setLanguage(Locale.US)
        }
    }

    companion object {
        private const val TAG = "SpeechOutput"
    }
}
