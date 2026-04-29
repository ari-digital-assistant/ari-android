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
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.models.ModelUpdateNotifier
import dev.heyari.ari.skills.SkillUpdateNotifier
import dev.heyari.ari.ui.AriNavHost
import dev.heyari.ari.ui.Routes
import dev.heyari.ari.ui.theme.AriTheme
import dev.heyari.ari.updates.UpdatesRepository
import dev.heyari.ari.wakeword.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var updatesRepository: UpdatesRepository

    // Channel, not SharedFlow: intents arrive before setContent runs, so we
    // need a buffer that survives until the NavHost collector shows up.
    // SharedFlow with replay=0 would drop the emission on the floor.
    private val deepLinkCommands = Channel<String>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Activity-lifetime scope for the launch-time bookkeeping (recordLaunch
    // does a DataStore write + cancels system notifications). We don't want
    // these tied to viewModelScope or a Compose effect because they should
    // run even if the user immediately presses back before the NavHost
    // commits its first composition.
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        logFsnPermission()

        if (isWakeWordIntent(intent)) {
            showOverLockScreen()
        }

        // The user is in-app: stamp lastLaunchedAt so the worker's
        // inactive-user gate stays accurate, and cancel any system
        // notifications about pending updates (the in-app banner takes
        // over from here).
        activityScope.launch { updatesRepository.recordLaunch() }

        handleWakeWordIntent(intent)
        handleSkillUpdatesIntent(intent)
        handleModelUpdatesIntent(intent)
        setContent {
            AriTheme {
                AriNavHost(
                    deepLinkCommands = deepLinkCommands.receiveAsFlow(),
                    settingsRepository = settingsRepository,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isWakeWordIntent(intent)) {
            showOverLockScreen()
        }
        handleWakeWordIntent(intent)
        handleSkillUpdatesIntent(intent)
        handleModelUpdatesIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // onCreate fires once per process, but the user can background +
        // foreground for days without restarting. Stamp lastLaunchedAt
        // here too so the inactive-user gate sees fresh activity on every
        // return-to-foreground.
        activityScope.launch { updatesRepository.recordLaunch() }
    }

    private fun handleSkillUpdatesIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(SkillUpdateNotifier.EXTRA_OPEN_SKILLS, false) == true) {
            // trySend is fine — the channel has capacity 1 with DROP_OLDEST,
            // so it never suspends and the most recent intent always wins.
            deepLinkCommands.trySend(Routes.skills())
            intent.removeExtra(SkillUpdateNotifier.EXTRA_OPEN_SKILLS)
        }
    }

    private fun handleModelUpdatesIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(ModelUpdateNotifier.EXTRA_OPEN_AUTO_UPDATE, false) == true) {
            deepLinkCommands.trySend(Routes.SETTINGS_AUTO_UPDATE)
            intent.removeExtra(ModelUpdateNotifier.EXTRA_OPEN_AUTO_UPDATE)
        }
    }

    override fun onStop() {
        super.onStop()
        // Clear lock-screen flags when the activity is no longer visible.
        // This is critical: if left set, Android keeps Ari's UID at
        // IMPORTANCE_VISIBLE even with the screen off, which causes the next
        // wake word's full-screen intent to be suppressed (degraded to a
        // heads-up notification). Clearing here matches Dicio's behaviour.
        setShowWhenLocked(false)
        setTurnScreenOn(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showOverLockScreen() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        Log.i(TAG, "Showing over lock screen for wake word")
    }

    private fun isWakeWordIntent(intent: Intent?): Boolean {
        return intent?.getBooleanExtra(WakeWordService.EXTRA_WAKE_WORD_DETECTED, false) == true
    }

    private fun handleWakeWordIntent(intent: Intent?) {
        // Wake word events go straight to the system overlay (VoiceSession)
        // now — the activity doesn't need to react. We still set the lock-screen
        // flags above so the activity can still come up if the user taps the
        // notification fallback.
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

    companion object {
        private const val TAG = "MainActivity"
    }
}
