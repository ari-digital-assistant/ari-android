package dev.heyari.ari.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R

/**
 * Says what Ari does with the microphone, before Android's permission prompt.
 *
 * Play's User Data policy wants background microphone use disclosed inside the
 * app, ahead of the request, and dismissed by a deliberate tap rather than a
 * back gesture — a privacy policy or a store listing doesn't count. Every path
 * that can ask for RECORD_AUDIO goes through this, not just onboarding: the
 * permission unlocks background listening whichever button led to it, so
 * granting it from the composer's mic tap deserves the same explanation as
 * granting it from the wizard.
 *
 * It only ever appears while the permission is ungranted, so in practice a
 * user sees it once.
 */
@Composable
fun MicDisclosureDialog(
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mic_disclosure_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.mic_disclosure_background),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.mic_disclosure_on_device),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.mic_disclosure_next),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.mic_disclosure_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.mic_disclosure_not_now))
            }
        },
    )
}
