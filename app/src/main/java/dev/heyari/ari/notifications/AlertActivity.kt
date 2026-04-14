package dev.heyari.ari.notifications

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.heyari.ari.assets.AssetResolver
import dev.heyari.ari.ui.theme.AriTheme
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Full-takeover alert UI. Launched via [android.app.Notification.fullScreenIntent]
 * when an alert with `fullTakeover && urgency == CRITICAL` fires.
 *
 * Stays generic — every field rendered comes from the [AlertSpec] the
 * emitting skill declared. Skills get a consistent alarm-clock-style
 * lock-screen takeover for free; they declare intent (`full_takeover: true`)
 * and the frontend renders. No skill-side platform code needed.
 *
 * Lock-screen behaviour: `setShowWhenLocked + setTurnScreenOn` mirror the
 * Google Clock pattern. The reserved `stop_alert` action fires
 * [AlertService.stopIntent] without requiring unlock — its `PendingIntent`
 * is `getService` which doesn't go through keyguard. Custom actions route
 * through [dev.heyari.ari.actions.NotificationActionReceiver] via
 * [PendingIntent.getBroadcast], also unlock-free. Tapping a custom action
 * that opens the main app will of course still need PIN entry — that's
 * Android's call, not ours.
 *
 * Auto-finish: observes [AlertRegistry] and finishes the moment its alert
 * leaves the active set (Stop tap, auto-stop cap, external dismissal).
 */
@AndroidEntryPoint
class AlertActivity : ComponentActivity() {

    @Inject lateinit var assetResolver: AssetResolver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Same lock-screen takeover flags VoiceOverlayActivity uses.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Asks the system to dismiss the keyguard when this activity
            // becomes visible — for non-secure (PIN/pattern-less) lock
            // screens it actually unlocks; for secure lockscreens it just
            // surfaces above the keyguard, same as setShowWhenLocked.
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        }

        val spec = intent?.getStringExtra(EXTRA_SPEC_JSON)?.let(AlertSpecCodec::decode)
        if (spec == null) {
            Log.w(TAG, "AlertActivity launched with no/malformed spec — finishing")
            finishAndRemoveTask()
            return
        }

        // Auto-finish when the alert id leaves the active set.
        lifecycleScope.launch {
            AlertRegistry.active.collect { active ->
                if (spec.id !in active) {
                    Log.i(TAG, "Alert ${spec.id} no longer active — finishing")
                    finishAndRemoveTask()
                }
            }
        }

        setContent {
            AriTheme {
                AlertTakeover(
                    spec = spec,
                    assetResolver = assetResolver,
                    onAction = { action -> dispatchAction(spec, action) },
                )
            }
        }
    }

    private fun dispatchAction(spec: AlertSpec, action: AlertAction) {
        when (action.id) {
            "stop_alert" -> {
                startService(AlertService.stopIntent(this, spec.id))
                // Don't finish here — the registry observer above will.
            }
            else -> {
                // Generic: route through NotificationActionReceiver which
                // also stops the alert (so the user isn't shouted at while
                // the engine works) and applies the engine response.
                val receiverIntent = dev.heyari.ari.actions.NotificationActionReceiver.intent(
                    context = this,
                    actionId = action.id,
                    utterance = action.utterance,
                    notificationIdToDismiss = null,
                    alertIdToStop = spec.id,
                )
                sendBroadcast(receiverIntent)
            }
        }
    }

    companion object {
        const val EXTRA_SPEC_JSON = "alert_spec_json"
        private const val TAG = "AlertActivity"

        fun intent(context: Context, spec: AlertSpec): Intent =
            Intent(context, AlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_SPEC_JSON, AlertSpecCodec.encode(spec))
    }
}

@Composable
private fun AlertTakeover(
    spec: AlertSpec,
    assetResolver: AssetResolver,
    onAction: (AlertAction) -> Unit,
) {
    val active by AlertRegistry.active.collectAsState()
    val isStillAlerting = spec.id in active

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top spacer pushes the content roughly into the top third —
            // matches the Google Clock alarm-fire layout.
            Spacer(modifier = Modifier.height(48.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f),
            ) {
                AlertIcon(spec = spec, assetResolver = assetResolver)
                Text(
                    text = spec.title,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                spec.body?.let { body ->
                    Text(
                        text = body,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!isStillAlerting) {
                    // Cap reached but the user hasn't dismissed yet —
                    // tell them so they don't think audio is broken.
                    Text(
                        text = "Stopped automatically.",
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ActionButtons(spec = spec, onAction = onAction)
        }
    }
}

@Composable
private fun AlertIcon(spec: AlertSpec, assetResolver: AssetResolver) {
    val icon = spec.icon ?: return
    val bitmap = remember(spec.id, icon) {
        assetResolver.resolve(spec.skillId, icon)?.let { file ->
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        }
    } ?: return
    Image(
        painter = BitmapPainter(bitmap.asImageBitmap()),
        contentDescription = null,
        modifier = Modifier.size(96.dp),
    )
}

@Composable
private fun ActionButtons(spec: AlertSpec, onAction: (AlertAction) -> Unit) {
    // If the skill declared no actions (which would be a bug — there'd be
    // no way out), fall back to a single "Dismiss" button that stops the
    // alert. Defensive only.
    val actions = spec.actions.ifEmpty {
        listOf(
            AlertAction(
                id = "stop_alert",
                label = "Dismiss",
                utterance = null,
                style = AlertAction.Style.PRIMARY,
            ),
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (action in actions) {
            AlertActionButton(action = action, onClick = { onAction(action) })
        }
    }
}

@Composable
private fun AlertActionButton(action: AlertAction, onClick: () -> Unit) {
    when (action.style) {
        AlertAction.Style.PRIMARY -> Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text(action.label, fontSize = 18.sp) }

        AlertAction.Style.DESTRUCTIVE -> Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { Text(action.label, fontSize = 18.sp) }

        AlertAction.Style.DEFAULT -> OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text(action.label, fontSize = 18.sp) }
    }
}
