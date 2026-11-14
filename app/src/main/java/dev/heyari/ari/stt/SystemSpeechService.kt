package dev.heyari.ari.stt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.util.Locale
import android.speech.SpeechRecognizer as AndroidSpeechRecognizer

/** A recognition service installed on the phone, as the settings picker lists it. */
data class RecognitionServiceOption(val component: String, val label: String)

/**
 * The recognition services installed on the phone, Ari's own left out: Ari is a
 * recognition service itself so it can be the default assistant, and handing
 * Ari its own audio would go round in a circle.
 */
fun installedRecognitionServices(context: Context): List<RecognitionServiceOption> {
    val pm = context.packageManager
    return pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        .filter { it.serviceInfo.packageName != context.packageName }
        .map {
            RecognitionServiceOption(
                component = ComponentName(it.serviceInfo.packageName, it.serviceInfo.name).flattenToString(),
                label = it.loadLabel(pm).toString(),
            )
        }
        .sortedBy { it.label.lowercase() }
}

/** How a recognition service's error reads to the user. */
internal enum class ServiceFailure {
    /** It heard nothing it could transcribe: the commonest false wake. */
    NO_SPEECH,

    /** It turned the request down: Google's do this to handed-over audio (#28). */
    REFUSED,

    /** Anything else went wrong. */
    FAILED,
}

internal fun serviceFailure(error: Int): ServiceFailure = when (error) {
    AndroidSpeechRecognizer.ERROR_NO_MATCH,
    AndroidSpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ServiceFailure.NO_SPEECH
    AndroidSpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
    AndroidSpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
    AndroidSpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
    AndroidSpeechRecognizer.ERROR_CLIENT -> ServiceFailure.REFUSED
    else -> ServiceFailure.FAILED
}

/**
 * The language to ask the service for: the phone's own tag ("en-GB") when it is
 * in Ari's language, so the service gets the region too, else Ari's bare
 * language code.
 */
internal fun serviceLanguageTag(ariLocale: String, device: Locale): String =
    if (device.language == ariLocale) device.toLanguageTag() else ariLocale

/** 16-bit PCM in the little-endian byte order the service reads. */
internal fun pcmBytes(chunk: ShortArray): ByteArray {
    val out = ByteArray(chunk.size * 2)
    for (i in chunk.indices) {
        val s = chunk[i].toInt()
        out[2 * i] = s.toByte()
        out[2 * i + 1] = (s shr 8).toByte()
    }
    return out
}

/**
 * One transcription by a recognition service, fed Ari's own audio through
 * `RecognizerIntent.EXTRA_AUDIO_SOURCE` (Android 13). Ari keeps the microphone,
 * so the service gets the pre-roll with "Hey Ari" in it and nothing is lost.
 *
 * Android's recogniser lives on the main thread, so every call to it is posted
 * there; its callbacks arrive there too. [write] blocks on the pipe and belongs
 * on a background thread. Once [close] has run, late callbacks are dropped.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class SystemSpeechSession(
    private val context: Context,
    private val component: ComponentName,
    private val language: String,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (Int) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val pipe = ParcelFileDescriptor.createPipe()
    private val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
    private var recognizer: AndroidSpeechRecognizer? = null

    @Volatile
    private var closed = false

    fun start() {
        main.post {
            if (closed) return@post
            val rec = AndroidSpeechRecognizer.createSpeechRecognizer(context, component)
            recognizer = rec
            rec.setRecognitionListener(listener)
            rec.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                },
            )
        }
    }

    /** Hand the service more audio. False once it has stopped reading. */
    fun write(chunk: ShortArray): Boolean = try {
        out.write(pcmBytes(chunk))
        true
    } catch (e: IOException) {
        Log.i(TAG, "service stopped reading: ${e.message}")
        false
    }

    /** End the audio, which tells the service to finish up. */
    fun finishAudio() {
        runCatching { out.close() }
    }

    fun close() {
        closed = true
        runCatching { out.close() }
        main.post {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            runCatching { pipe[0].close() }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (closed) return
            val text = partialResults?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text != null) onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            if (closed) return
            onFinal(results?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
        }

        override fun onError(error: Int) {
            if (closed) return
            Log.i(TAG, "${component.flattenToShortString()} error $error")
            this@SystemSpeechSession.onError(error)
        }
    }

    private companion object {
        const val TAG = "SystemSpeechSession"
        const val SAMPLE_RATE = 16000
    }
}
