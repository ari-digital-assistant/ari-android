package dev.heyari.ari.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService

/**
 * Stub recognition service. Required to be declared in the manifest for the app
 * to qualify for the digital assistant role. We don't actually do any system-level
 * recognition here — Ari has its own STT pipeline (sherpa-onnx) used internally.
 */
class AriRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // No-op stub. Return an empty result so the system doesn't hang.
        listener?.results(Bundle())
    }

    override fun onCancel(listener: Callback?) {
        // No-op
    }

    override fun onStopListening(listener: Callback?) {
        listener?.results(Bundle())
    }
}
