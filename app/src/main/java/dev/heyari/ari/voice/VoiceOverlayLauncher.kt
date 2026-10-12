package dev.heyari.ari.voice

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.heyari.ari.wakeword.WakeWordService

/**
 * Opens [VoiceOverlayActivity], possibly from the background or over the lock
 * screen. Two callers: the wake service, at a wake or a tap on the mic, and the
 * voice session, when a quiet wake has heard its name and is ready to show
 * itself.
 *
 * From the background this relies on SYSTEM_ALERT_WINDOW, which grants the
 * whole app the right to start activities; the wake service checks for it
 * before a wake gets this far.
 */
internal object VoiceOverlayLauncher {
    private const val TAG = "VoiceOverlayLauncher"

    /** True if the launch was dispatched. */
    fun launch(context: Context, verifyWake: Boolean): Boolean {
        val intent = Intent(context, VoiceOverlayActivity::class.java).apply {
            putExtra(WakeWordService.EXTRA_VERIFY_WAKE, verifyWake)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    or Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        @Suppress("DEPRECATION")
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= 34) {
                // Deprecated in API 36 in favour of an as-yet-unstable replacement.
                // The constant still works and SAW is the actual BAL grant anyway;
                // this just makes the intent explicit for OEM hardening.
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
        }.toBundle()

        return try {
            context.startActivity(intent, options)
            Log.i(TAG, "Voice overlay activity launched")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to launch voice overlay activity", t)
            false
        }
    }
}
