package dev.heyari.ari.ui.conversation

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Bottom "presence" aura layered behind the composer. Its intensity and breath
 * period follow [state]: a slow faint breath at Idle, a lively pulse while
 * Listening, a medium pulse while Thinking, a quicker rhythm while Speaking.
 *
 * Reduce-motion aware: when the system animator scale is 0 (Developer Options →
 * "Animator duration scale: off", or accessibility reduce-motion), the aura
 * renders as a static tint at its base intensity instead of pulsing. The gate
 * is read once at first composition via [animationsEnabled]; the value is
 * stable across recompositions, so the branch structure below stays consistent.
 *
 * Dynamic-colour clean: the aura is drawn from [MaterialTheme.colorScheme.primary]
 * so it tracks the active Material You palette (no hardcoded colours).
 */
@Composable
fun AmbientField(state: AmbientState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val motion = remember { animationsEnabled(ctx) }
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary

    val (baseAlpha, period) = when (state) {
        AmbientState.Idle -> 0.10f to 5500
        AmbientState.Listening -> 0.26f to 1600
        AmbientState.Thinking -> 0.16f to 2200
        AmbientState.Speaking -> 0.24f to 1100
    }
    val alpha = if (!motion) baseAlpha else {
        val t = rememberInfiniteTransition(label = "aura")
        val v by t.animateFloat(
            initialValue = baseAlpha * 0.5f, targetValue = baseAlpha,
            animationSpec = infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Reverse),
            label = "auraAlpha",
        )
        v
    }
    Box(
        modifier = modifier.fillMaxWidth().height(120.dp).drawBehind {
            drawRect(brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = alpha), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 1.3f),
                radius = size.width * 0.9f,
            ))
        }
    )
}

/**
 * Whether decorative animations should run. Honours the system animator
 * duration scale — a scale of 0 (Developer Options, or reduce-motion
 * accessibility settings that map onto it) means "no animations", so the
 * caller falls back to a static rendering.
 */
fun animationsEnabled(context: android.content.Context): Boolean =
    android.provider.Settings.Global.getFloat(
        context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) != 0f
