package dev.heyari.ari.wakeword

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import dev.heyari.ari.MainActivity

/**
 * Tiny launcher activity used as the target for the wake-word full-screen
 * notification. Existing in its own task affinity (and never staying in
 * recents) means Android's FSN policy always treats it as "sleeping", so the
 * full-screen intent fires reliably even when MainActivity is already in
 * memory and was the most recently focused app at lock time.
 *
 * It does nothing but immediately forward to MainActivity with the wake-word
 * extra and finish.
 */
class WakeTrampolineActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Trampoline launched, forwarding to MainActivity")

        val target = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra(WakeWordService.EXTRA_WAKE_WORD_DETECTED, true)
        }
        startActivity(target)
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "WakeTrampoline"
    }
}
