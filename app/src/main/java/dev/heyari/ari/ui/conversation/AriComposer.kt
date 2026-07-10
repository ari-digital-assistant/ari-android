package dev.heyari.ari.ui.conversation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R

/**
 * Composer-centric input row: a pill-shaped text field with a single
 * trailing action button that swaps between mic (blank input) and send
 * (non-blank input), per [composerAction].
 */
@Composable
fun AriComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.conversation_input_placeholder)) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        val action = composerAction(value)
        IconButton(onClick = { if (action == ComposerAction.Send) onSend() else onMicTap() }) {
            when (action) {
                ComposerAction.Send -> Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.conversation_send),
                )
                ComposerAction.Mic -> Icon(
                    Icons.Default.Mic,
                    contentDescription = stringResource(R.string.conversation_talk),
                )
            }
        }
    }
}
