package dev.heyari.ari.ui.conversation

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Transient "still working" bubble: three softly blinking dots in an
 * Ari-side bubble with the avatar gutter, mirroring [MessageBubble]'s
 * assistant styling. Purely transient UI — driven by
 * [dev.heyari.ari.model.ConversationState.isThinking] and never appended
 * to the conversation log, so it can't survive into the record.
 */
@Composable
fun ThinkingIndicator(modifier: Modifier = Modifier) {
    // Reduce-motion gate, same helper the aura/entrance use. Read once at first
    // composition (stable across recompositions) so the branch structure below
    // stays consistent — no rememberInfiniteTransition at all when disabled.
    val context = LocalContext.current
    val motion = remember { animationsEnabled(context) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        AriAvatar(modifier = Modifier.padding(end = 8.dp))
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 4.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            tonalElevation = 2.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (motion) {
                    val t = rememberInfiniteTransition(label = "dots")
                    repeat(3) { i ->
                        val a by t.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(tween(500, delayMillis = i * 150), RepeatMode.Reverse),
                            label = "dot$i",
                        )
                        Dot(alpha = a)
                    }
                } else {
                    // Static fallback: three steady dots, no infinite transition.
                    repeat(3) { Dot(alpha = 0.6f) }
                }
            }
        }
    }
}

@Composable
private fun Dot(alpha: Float) {
    Box(
        Modifier
            .size(6.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}
