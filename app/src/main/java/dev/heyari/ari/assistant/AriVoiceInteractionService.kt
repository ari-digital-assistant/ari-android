package dev.heyari.ari.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Empty VoiceInteractionService — required for the app to qualify as a digital
 * assistant. All real work happens in [AriVoiceInteractionSessionService] /
 * [AriVoiceInteractionSession]. This class only exists to be bound by the system
 * after the user grants the assistant role.
 */
class AriVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "Ari voice interaction service ready")
    }

    companion object {
        private const val TAG = "AriVoiceInteraction"
    }
}
