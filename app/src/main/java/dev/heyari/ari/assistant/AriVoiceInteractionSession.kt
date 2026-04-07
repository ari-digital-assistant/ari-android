package dev.heyari.ari.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import dev.heyari.ari.MainActivity

/**
 * Session shown when the user invokes the assist gesture (long-press home,
 * power button on some devices, etc.) while Ari is the selected digital
 * assistant. We just open MainActivity and finish the session — the
 * conversation screen handles everything else.
 *
 * Future: we could pre-trigger STT here so the user doesn't even need to tap
 * the mic — but for now, simple open-the-app behaviour matches what the user
 * expects from a default assistant.
 */
class AriVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_FROM_ASSIST, true)
        }
        context.startActivity(intent)
        finish()
    }

    companion object {
        const val EXTRA_FROM_ASSIST = "ari_from_assist"
    }
}
