package dev.heyari.ari.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession

/**
 * Session shown when the user invokes the assist gesture (a swipe up from a
 * bottom corner, or the power button where the phone offers it) while Ari is
 * the selected digital assistant. It starts a voice turn, so Ari comes up
 * already listening, as other assistants do, and finishes the session.
 *
 * The turn starts from [AssistTurnActivity] rather than here: a microphone
 * service may only be started from the foreground, and an activity is.
 */
class AriVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        val intent = Intent(context, AssistTurnActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        finish()
    }

    companion object {
        const val EXTRA_FROM_ASSIST = "ari_from_assist"
    }
}
