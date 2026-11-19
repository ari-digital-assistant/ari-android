package dev.heyari.ari.ui.conversation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R
import uniffi.ari_ffi.FfiSlashCommand

/**
 * Composer-centric input row: a pill-shaped text field with a single trailing
 * action button. The button swaps between Mic (blank input → start dictation),
 * Send (non-blank input), and a live pulsing recording light (while dictating →
 * tap to stop), per [composerAction]. The placeholder says whether Ari is
 * listening: "I'm listening…" while dictating, otherwise whether the wake
 * word is armed (see [composerPlaceholder]).
 *
 * Typing `/` lists Ari's built-in [slashCommands], narrowed as the name is
 * typed (see [slashSuggestions]). Picking one that needs words after it fills
 * it in; any other is sent at once through [onSendCommand].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AriComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendCommand: (String) -> Unit,
    onMicTap: () -> Unit,
    onStop: () -> Unit,
    isDictating: Boolean,
    micEnabled: Boolean,
    wakeArmed: Boolean,
    wakePhrase: String,
    ambientState: AmbientState,
    slashCommands: List<FfiSlashCommand>,
    modifier: Modifier = Modifier,
) {
    // The field's own value, so a picked command can put the cursor after
    // itself. Its text always follows [value], as the String overload's does.
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    val suggestions = slashSuggestions(value, slashCommands)
    var dismissed by remember(value) { mutableStateOf(false) }
    val menuOpen = suggestions.isNotEmpty() && !dismissed
    Row(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExposedDropdownMenuBox(
            expanded = menuOpen,
            onExpandedChange = {},
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = field.copy(text = value),
                onValueChange = {
                    field = it
                    onValueChange(it.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    .ambientComposerBorder(ambientState),
                placeholder = {
                    Text(
                        when (composerPlaceholder(isDictating, wakeArmed)) {
                            ComposerPlaceholder.Dictating -> stringResource(R.string.conversation_listening_placeholder)
                            ComposerPlaceholder.WakeArmed ->
                                stringResource(R.string.conversation_input_placeholder_listening, wakePhrase)
                            ComposerPlaceholder.NotListening -> stringResource(R.string.conversation_input_placeholder)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Italian runs longer than English and was cut off on a
                        // Pixel 9 Pro (ari-android#35). Shrink to fit, down to
                        // bodySmall's size; past that, ellipsis.
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = MaterialTheme.typography.bodySmall.fontSize,
                            maxFontSize = LocalTextStyle.current.fontSize,
                        ),
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
            ExposedDropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { dismissed = true },
            ) {
                suggestions.forEach { command ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("/${command.name}")
                                Text(
                                    command.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            if (command.takesArgument) {
                                val text = "/${command.name} "
                                field = TextFieldValue(text, TextRange(text.length))
                                onValueChange(text)
                            } else {
                                onSendCommand("/${command.name}")
                            }
                        },
                    )
                }
            }
        }
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

/** Which placeholder the composer shows. */
internal enum class ComposerPlaceholder { Dictating, WakeArmed, NotListening }

/**
 * Dictation wins, since the mic is open for this field. Otherwise the field
 * says whether the wake word is armed: with Ari's glow now steady while it
 * waits (ari-android#32), the words carry that it is listening.
 */
internal fun composerPlaceholder(isDictating: Boolean, wakeArmed: Boolean): ComposerPlaceholder = when {
    isDictating -> ComposerPlaceholder.Dictating
    wakeArmed -> ComposerPlaceholder.WakeArmed
    else -> ComposerPlaceholder.NotListening
}

/**
 * The commands to offer while [input] is a `/` and the start of a command's
 * name. Once a space follows the name, the user is past choosing.
 */
internal fun slashSuggestions(input: String, commands: List<FfiSlashCommand>): List<FfiSlashCommand> {
    if (!input.startsWith("/") || input.any { it.isWhitespace() }) return emptyList()
    val typed = input.drop(1)
    return commands.filter { it.name.startsWith(typed, ignoreCase = true) }
}
