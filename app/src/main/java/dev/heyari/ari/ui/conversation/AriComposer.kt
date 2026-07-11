package dev.heyari.ari.ui.conversation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R

/**
 * Composer-centric input row: a pill-shaped text field with a single trailing
 * action button. The button swaps between Mic (blank input → start dictation),
 * Send (non-blank input), and a live pulsing recording light (while dictating →
 * tap to stop), per [composerAction]. While dictating, the field placeholder
 * also switches to "I'm listening…".
 */
@Composable
fun AriComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicTap: () -> Unit,
    onStop: () -> Unit,
    isDictating: Boolean,
    micEnabled: Boolean,
    ambientState: AmbientState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .ambientComposerBorder(ambientState),
            placeholder = {
                Text(
                    stringResource(
                        if (isDictating) R.string.conversation_listening_placeholder
                        else R.string.conversation_input_placeholder
                    )
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        val action = composerAction(value, isDictating)
        IconButton(
            onClick = {
                when (action) {
                    ComposerAction.Send -> onSend()
                    ComposerAction.Stop -> onStop()
                    ComposerAction.Mic -> onMicTap()
                }
            },
            enabled = action != ComposerAction.Mic || micEnabled,
        ) {
            when (action) {
                ComposerAction.Send -> Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.conversation_send),
                )
                ComposerAction.Stop -> RecordingIndicator()
                ComposerAction.Mic -> Icon(
                    Icons.Default.Mic,
                    contentDescription = stringResource(R.string.conversation_talk),
                )
            }
        }
    }
}

/**
 * A live recording light for the dictating state: a filled accent dot that
 * pulses (fades in and out) to signal the mic is hot. Doubles as the Stop
 * button — the enclosing [IconButton]'s tap stops dictation. Reduce-motion
 * aware: renders as a steady dot when the system animator scale is 0.
 */
@Composable
private fun RecordingIndicator() {
    val ctx = LocalContext.current
    val motion = remember { animationsEnabled(ctx) }
    val stopLabel = stringResource(R.string.conversation_stop_dictation)
    val alpha = if (!motion) 1f else {
        val transition = rememberInfiniteTransition(label = "recording")
        val a by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
            label = "recordingAlpha",
        )
        a
    }
    Box(
        modifier = Modifier
            .size(14.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .semantics { contentDescription = stopLabel },
    )
}
