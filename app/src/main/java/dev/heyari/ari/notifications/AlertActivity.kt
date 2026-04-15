package dev.heyari.ari.notifications

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.heyari.ari.R
import dev.heyari.ari.assets.AssetResolver
import dev.heyari.ari.ui.theme.AriTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

/**
 * Full-takeover alert UI. Launched via [android.app.Notification.fullScreenIntent]
 * when an alert with `fullTakeover && urgency == CRITICAL` fires.
 *
 * Every visual element is generic — pulled from the [AlertSpec] the skill
 * declared or from device-level state (system clock). Third-party skills
 * that emit `full_takeover: true` alerts get the same alarm-clock-style
 * takeover for free. The only skill-facing polish hook is the optional
 * `icon` field in the alert primitive; without it the icon slot simply
 * collapses and the rest of the layout still makes sense.
 *
 * Layout (top → bottom):
 *  - Current wall clock time, large monospace.
 *  - Pulsing icon (skill-provided asset), title, body, "Alerted at …"
 *    caption.
 *  - Full-width primary action button(s).
 *
 * Background: vertical gradient from `errorContainer` at the top to
 * `surface` by ~40% down for critical urgency; plain surface otherwise.
 * Signals urgency without drowning the content.
 *
 * Lock-screen: `setShowWhenLocked + setTurnScreenOn + requestDismissKeyguard`
 * mirror the Google Clock pattern. `stop_alert` fires a service
 * PendingIntent so it works without unlock; custom actions route through
 * NotificationActionReceiver (broadcast, also unlock-free).
 *
 * Auto-finish: observes [AlertRegistry] and finishes the moment its
 * alert leaves the active set (Stop tap, auto-stop cap, external
 * dismissal).
 */
@AndroidEntryPoint
class AlertActivity : ComponentActivity() {

    @Inject lateinit var assetResolver: AssetResolver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        }

        val spec = intent?.getStringExtra(EXTRA_SPEC_JSON)?.let(AlertSpecCodec::decode)
        if (spec == null) {
            Log.w(TAG, "AlertActivity launched with no/malformed spec — finishing")
            finishAndRemoveTask()
            return
        }

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
            }
            else -> {
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
    // Captured once at first composition — the instant the takeover
    // appeared, which is within milliseconds of the alert actually
    // firing. Used for the "Alerted at HH:MM" caption so the user has
    // a time anchor if they took a moment to look at the phone.
    val firedAtMs = remember { System.currentTimeMillis() }

    val background = takeoverBackground(spec.urgency)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            CurrentTime()
            AlertHero(
                spec = spec,
                assetResolver = assetResolver,
                firedAtMs = firedAtMs,
            )
            ActionButtons(spec = spec, onAction = onAction)
        }
    }
}

/**
 * Live wall-clock at the top of the takeover. Ticks every 30s so the
 * user sees a correct time whether they woke the device a second ago or
 * two minutes after the alert first fired. Monospace so the digits don't
 * jitter width.
 */
@Composable
private fun CurrentTime() {
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    val nowMs = liveClockMs()
    Text(
        text = timeFormat.format(Date(nowMs.value)),
        style = MaterialTheme.typography.displayMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            fontSize = 44.sp,
        ),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
    )
}

@Composable
private fun liveClockMs(): State<Long> =
    produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            val now = System.currentTimeMillis()
            value = now
            // Sleep to the next minute-boundary, plus a tiny jitter so we
            // update right as the minute flips rather than drifting.
            val msToNextMinute = 60_000L - (now % 60_000L)
            delay(msToNextMinute.coerceAtLeast(1_000L))
        }
    }

/**
 * The middle of the takeover: icon + pulse, title, optional body, and
 * the "Alerted at HH:MM" caption.
 */
@Composable
private fun AlertHero(
    spec: AlertSpec,
    assetResolver: AssetResolver,
    firedAtMs: Long,
) {
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        PulsingIcon(spec = spec, assetResolver = assetResolver)
        Text(
            text = spec.title,
            fontSize = 36.sp,
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
        Text(
            text = "Alerted at ${timeFormat.format(Date(firedAtMs))}",
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/**
 * Skill icon with a breathing ring animation. The ring pulses at
 * ~0.7 Hz so the static takeover feels alive without competing with
 * the audio. Skills that emit `icon: "asset:..."` on their alert
 * primitive get their glyph rendered; skills that don't (or whose
 * asset doesn't resolve — e.g. the `/alert-demo` debug path with no
 * bundle) fall back to a generic alarm-bell so the slot doesn't
 * collapse into dead middle space.
 */
@Composable
private fun PulsingIcon(spec: AlertSpec, assetResolver: AssetResolver) {
    val bitmap = remember(spec.id, spec.icon) {
        spec.icon?.let { icon ->
            assetResolver.resolve(spec.skillId, icon)?.let { file ->
                runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            }
        }
    }

    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseAlpha",
    )

    Box(
        modifier = Modifier.size(168.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Outer pulse ring — expanding + fading halo.
        Box(
            modifier = Modifier
                .size(168.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(
                    MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha),
                ),
        )
        // Inner static background behind the icon — keeps the icon legible
        // against the gradient backdrop.
        Box(
            modifier = Modifier
                .size(128.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
        )
        if (bitmap != null) {
            Image(
                painter = BitmapPainter(bitmap.asImageBitmap()),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_alert_bell),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onErrorContainer),
                modifier = Modifier.size(72.dp),
            )
        }
    }
}

@Composable
private fun ActionButtons(spec: AlertSpec, onAction: (AlertAction) -> Unit) {
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
            modifier = Modifier.fillMaxWidth().height(72.dp),
        ) { Text(action.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }

        AlertAction.Style.DESTRUCTIVE -> Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(72.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { Text(action.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }

        AlertAction.Style.DEFAULT -> OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(72.dp),
        ) { Text(action.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/**
 * Background brush driven by urgency — critical gets a warm top edge
 * fading into surface, keeping the middle content fully legible.
 * Non-critical urgencies stay flat so the screen doesn't shout when
 * it doesn't need to.
 */
@Composable
private fun takeoverBackground(urgency: AlertSpec.Urgency): Brush {
    val top = when (urgency) {
        AlertSpec.Urgency.CRITICAL -> MaterialTheme.colorScheme.errorContainer
        AlertSpec.Urgency.HIGH -> MaterialTheme.colorScheme.tertiaryContainer
        AlertSpec.Urgency.NORMAL -> MaterialTheme.colorScheme.surface
    }
    val bottom: Color = MaterialTheme.colorScheme.surface
    return Brush.verticalGradient(
        colorStops = arrayOf(
            0f to top,
            0.4f to bottom,
            1f to bottom,
        ),
    )
}
