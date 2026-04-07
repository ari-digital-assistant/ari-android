package dev.heyari.ari.voice

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.heyari.ari.ui.theme.AriTheme
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Transparent activity that hosts the voice session overlay UI. Launched
 * directly from [dev.heyari.ari.wakeword.WakeWordService] when the wake word
 * fires. Lives in its own task affinity, never appears in recents, and is
 * marked showWhenLocked so it can take over the lock screen reliably.
 *
 * The activity does not own the voice session lifecycle — [VoiceSession] is a
 * Hilt singleton, the activity just observes its state and finishes itself
 * when the state returns to [VoiceState.Idle].
 */
@AndroidEntryPoint
class VoiceOverlayActivity : ComponentActivity() {

    @Inject
    lateinit var voiceSession: VoiceSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lock-screen takeover. These flags must be set before super.onCreate
        // on older Android, but ComponentActivity handles them via setShowWhenLocked.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        Log.i(TAG, "VoiceOverlayActivity created")

        // Start the session and observe state to auto-finish on Idle.
        voiceSession.start()

        lifecycleScope.launch {
            voiceSession.state.collect { state ->
                if (state is VoiceState.Idle) {
                    Log.i(TAG, "Voice session returned to Idle — finishing activity")
                    finishAndRemoveTask()
                }
            }
        }

        setContent {
            AriTheme {
                val state by voiceSession.state.collectAsState()
                VoiceOverlayContent(
                    state = state,
                    onDismiss = { voiceSession.dismiss() },
                )
            }
        }
    }

    override fun onDestroy() {
        // Make sure the session ends if the activity is killed by something
        // outside our control (back press, system kill, etc.).
        if (voiceSession.isActive) {
            voiceSession.dismiss()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VoiceOverlayActivity"
    }
}
