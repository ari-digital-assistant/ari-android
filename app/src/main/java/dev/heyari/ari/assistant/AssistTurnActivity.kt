package dev.heyari.ari.assistant

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.heyari.ari.MainActivity
import dev.heyari.ari.voice.VoiceSession
import dev.heyari.ari.wakeword.WakeWordService
import javax.inject.Inject

/**
 * No-UI trampoline for the assistant gesture: starts a voice turn, as saying
 * "Hey Ari" would, then finishes. The overlay comes up listening over whatever
 * was on screen, as other assistants' do.
 *
 * An activity because Android only lets a foreground app start a
 * microphone-typed service, and an activity in onCreate is unambiguously
 * foreground (the same reason [dev.heyari.ari.wakeword.StartListeningActivity]
 * exists). Without the microphone permission it opens Ari instead, where the
 * user can grant it.
 */
@AndroidEntryPoint
class AssistTurnActivity : ComponentActivity() {

    @Inject
    lateinit var voiceSession: VoiceSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!WakeWordService.startVoiceTurn(this, sessionActive = voiceSession.isActive)) {
            Log.i(TAG, "No microphone permission — opening Ari instead of a voice turn")
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(AriVoiceInteractionSession.EXTRA_FROM_ASSIST, true)
                },
            )
        }
        finish()
        suppressCloseAnimation()
    }

    private fun suppressCloseAnimation() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private companion object {
        const val TAG = "AssistTurn"
    }
}
