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
import dev.heyari.ari.assets.AssetResolver
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.data.card.CardStateRepository
import dev.heyari.ari.ui.theme.AriTheme
import dev.heyari.ari.wakeword.WakeWordService
import kotlinx.coroutines.flow.combine
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
 * when the state returns to [VoiceState.Idle] with no answer's cards left
 * on it ([VoiceSession.lingering]).
 */
@AndroidEntryPoint
class VoiceOverlayActivity : ComponentActivity() {

    @Inject
    lateinit var voiceSession: VoiceSession

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var cardRepository: CardStateRepository

    @Inject
    lateinit var assetResolver: AssetResolver

    // The session this overlay showed. A wake while an answer's cards linger
    // starts the next session, which this overlay must neither show nor end.
    private var session = 0

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

        // Start the session and observe state to auto-finish on Idle. An overlay
        // recreated by a rotation (or any configuration change) carries on with
        // the session it showed: starting one would open a wake turn that hears
        // Ari's own reply and counts it as a false wake.
        session = savedInstanceState?.getInt(KEY_SESSION) ?: run {
            voiceSession.start(
                verifyWake = intent.getBooleanExtra(WakeWordService.EXTRA_VERIFY_WAKE, false)
            )
            voiceSession.sessionId
        }

        lifecycleScope.launch {
            combine(voiceSession.state, voiceSession.lingering, ::Pair).collect { (state, lingering) ->
                when {
                    // A later session: a quiet wake, unseen until it hears its
                    // name, or one with an overlay of its own replacing this.
                    voiceSession.sessionId != session -> if (!isFinishing) finishAndRemoveTask()
                    state !is VoiceState.Idle -> Unit
                    lingering == null -> {
                        Log.i(TAG, "Voice session returned to Idle — finishing activity")
                        finishAndRemoveTask()
                    }
                    // Only the answer's cards are left: the screen may sleep as
                    // usual, which closes them (onStop).
                    else -> window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        setContent {
            val pureBlack by settingsRepository.pureBlack.collectAsState(initial = false)
            AriTheme(pureBlack = pureBlack) {
                val state by voiceSession.state.collectAsState()
                val lingering by voiceSession.lingering.collectAsState()
                VoiceOverlayContent(
                    state = if (state is VoiceState.Idle) lingering ?: state else state,
                    onDismiss = {
                        if (state is VoiceState.Idle) voiceSession.dropCards() else voiceSession.dismiss()
                    },
                    cardRepository = cardRepository,
                    assetResolver = assetResolver,
                    onCardAction = voiceSession::onCardAction,
                    onHoldCards = voiceSession::holdCards,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SESSION, session)
    }

    override fun onStop() {
        super.onStop()
        // An answer's cards go once the overlay is out of sight: the screen
        // went off, or the user went elsewhere. A rotation is neither.
        if (!isChangingConfigurations && voiceSession.sessionId == session && !voiceSession.isActive) {
            voiceSession.dropCards()
        }
    }

    override fun onDestroy() {
        // Make sure the session ends if the activity is killed by something
        // outside our control (back press, system kill, etc.). Only this
        // overlay's own session: a later one may already be running. Not for
        // a rotation, whose new overlay takes the session over.
        if (!isChangingConfigurations && voiceSession.isActive && voiceSession.sessionId == session) {
            voiceSession.dismiss()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VoiceOverlayActivity"
        private const val KEY_SESSION = "session"
    }
}
