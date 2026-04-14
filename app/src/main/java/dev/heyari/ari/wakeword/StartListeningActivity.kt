package dev.heyari.ari.wakeword

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

/**
 * No-UI trampoline for the "tap to start listening" recovery notification.
 * Android 14+ blocks mic-typed foreground services from starting in background
 * contexts (BOOT_COMPLETED, notification PendingIntents, etc.), but an activity
 * in onCreate is unambiguously a foreground context — so starting the service
 * from here is the one reliable path that works on locked-down stock-ish
 * Android (including GrapheneOS). The activity has no UI and finishes
 * immediately.
 */
class StartListeningActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Tap-to-start trampoline — launching WakeWordService")
        ContextCompat.startForegroundService(
            this,
            Intent(this, WakeWordService::class.java)
        )
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

    companion object {
        private const val TAG = "StartListening"
    }
}
