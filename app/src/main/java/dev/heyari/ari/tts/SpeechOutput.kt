package dev.heyari.ari.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Utterances awaiting their `onDone`/`onError` callback, keyed by
     * utteranceId. [speakAndAwait] registers one and suspends on it so a
     * caller can re-arm the mic only AFTER Ari has stopped talking — without
     * this, the STT rewind ingests Ari's own speech ("…to use, Apple Music").
     */
    private val pendingDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

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
            // One persistent listener for the engine's lifetime: it resolves
            // any pending speakAndAwait() and re-applies the voice after a
            // preview utterance. (Previously preview() installed its own
            // listener; a single owner avoids one path clobbering the other.)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finishUtterance(utteranceId)
                // stop() and QUEUE_FLUSH interrupt an utterance without ever
                // firing onDone. Without this, every cut-off utterance left its
                // waiter sitting on the safety timeout below.
                override fun onStop(utteranceId: String?, interrupted: Boolean) =
                    finishUtterance(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finishUtterance(utteranceId)
            })
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

        // The persistent listener re-applies the voice when a preview id
        // finishes (see finishUtterance).
        val id = "ari-preview-${utteranceId.incrementAndGet()}"
        tts.speak("Hello, I'm Ari", TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun speak(text: String) {
        if (!ready) {
            Log.w(TAG, "TTS not ready, dropping: $text")
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "ari-${utteranceId.incrementAndGet()}")
    }

    /**
     * Speak [text] and suspend until the TTS engine reports it finished,
     * stopped or errored (or a length-based safety timeout elapses, in case the
     * engine never reports back at all).
     * Used by the voice session before re-arming the mic for a follow-up, so
     * the mic opens only after Ari has stopped speaking — never capturing its
     * own voice. Uses QUEUE_FLUSH: the prompt is the only thing that should be
     * playing at this point.
     */
    suspend fun speakAndAwait(text: String) {
        if (!ready) {
            Log.w(TAG, "TTS not ready, dropping: $text")
            return
        }
        // QUEUE_FLUSH on nothing is not a no-op: it cancels whatever is
        // already playing or queued. A Layer C phase-1 turn is deliberately
        // silent, and speaking its empty reply here killed the phase-2 answer
        // the conversation viewmodel had just queued.
        if (text.isBlank()) return
        val id = "ari-${utteranceId.incrementAndGet()}"
        val done = CompletableDeferred<Unit>()
        pendingDone[id] = done
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        // Last resort for an engine that goes silent on us — roughly twice
        // how long the text takes to read at the default rate. The ceiling is
        // deliberately far beyond any real reply: callers treat a timeout as
        // "finished speaking", so a tight cap cuts a long answer off mid-word
        // rather than protecting anything. Cancellation, not this, is what ends
        // an utterance early.
        val maxMs = (text.length * 120L).coerceIn(4_000L, 120_000L)
        if (withTimeoutOrNull(maxMs) { done.await() } == null) {
            Log.w(TAG, "speakAndAwait timed out after ${maxMs}ms for id=$id")
        }
        pendingDone.remove(id)
    }

    private fun finishUtterance(id: String?) {
        id ?: return
        pendingDone.remove(id)?.complete(Unit)
        // Preview restores the user's real voice once the sample finishes.
        if (id.startsWith("ari-preview")) applyVoice()
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
