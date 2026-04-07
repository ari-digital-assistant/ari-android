package dev.heyari.ari

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.heyari.ari.ui.AriNavHost
import dev.heyari.ari.ui.theme.AriTheme
import dev.heyari.ari.wakeword.WakeWordEvents
import dev.heyari.ari.wakeword.WakeWordService
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var wakeWordEvents: WakeWordEvents

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        logFsnPermission()

        if (isWakeWordIntent(intent)) {
            showOverLockScreen()
        }

        handleWakeWordIntent(intent)
        setContent {
            AriTheme {
                AriNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isWakeWordIntent(intent)) {
            showOverLockScreen()
        }
        handleWakeWordIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        // Critical: clear lock-screen flags as soon as we leave foreground.
        // If left set, Android keeps the activity in a quasi-foreground state
        // and refuses to fire subsequent full-screen intents on top of it
        // (instead degrading them to heads-up notifications).
        clearLockScreenFlags()
    }

    private fun showOverLockScreen() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        Log.i(TAG, "Showing over lock screen for wake word")
    }

    private fun clearLockScreenFlags() {
        setShowWhenLocked(false)
        setTurnScreenOn(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun isWakeWordIntent(intent: Intent?): Boolean {
        return intent?.getBooleanExtra(WakeWordService.EXTRA_WAKE_WORD_DETECTED, false) == true
    }

    private fun handleWakeWordIntent(intent: Intent?) {
        if (isWakeWordIntent(intent)) {
            wakeWordEvents.onWakeWordDetected()
        }
    }

    private fun logFsnPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NotificationManager::class.java)
            val canFsn = nm.canUseFullScreenIntent()
            Log.i(TAG, "Full-screen intent permission: $canFsn")
        } else {
            Log.i(TAG, "Full-screen intent: not required (API < 34)")
        }
    }

    override fun onDestroy() {
        clearLockScreenFlags()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
