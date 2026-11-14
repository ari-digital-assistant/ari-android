package dev.heyari.ari.ui.settings.pages

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.data.ImportOutcome
import dev.heyari.ari.ui.settings.ImportState
import dev.heyari.ari.ui.settings.SettingsTransferViewModel
import java.time.LocalDate

/** The picker filter for imports. Providers label JSON inconsistently, so any file; the import rejects the wrong ones. */
internal val IMPORT_MIME_TYPES = arrayOf("*/*")

@Composable
internal fun SettingsBackupSection(viewModel: SettingsTransferViewModel) {
    val context = LocalContext.current
    val exportMessage by viewModel.exportMessage.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::export) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::import) }

    LaunchedEffect(exportMessage) {
        val message = exportMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.consumeExportMessage()
    }

    ImportDialog(
        state = importState,
        doneLabel = stringResource(R.string.settings_import_restart),
        onDone = viewModel::restart,
        onDismiss = viewModel::dismissImport,
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_backup_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_backup_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = { importLauncher.launch(IMPORT_MIME_TYPES) }) {
                    Text(stringResource(R.string.settings_backup_import))
                }
                Button(onClick = { exportLauncher.launch("ari-settings-${LocalDate.now()}.json") }) {
                    Text(stringResource(R.string.settings_backup_export))
                }
            }
        }
    }
}

/**
 * Progress and result of an import. A finished import can only go forward
 * through [onDone]: the settings are already written, and Ari needs the
 * restart that comes after it to use them.
 */
@Composable
internal fun ImportDialog(
    state: ImportState,
    doneLabel: String,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        ImportState.Idle -> Unit
        ImportState.Importing -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.settings_import_working))
                }
            },
        )
        ImportState.Unreadable -> ImportFailedDialog(R.string.settings_import_unreadable, onDismiss)
        is ImportState.Finished -> when (val outcome = state.outcome) {
            ImportOutcome.NotAnExport -> ImportFailedDialog(R.string.settings_import_not_an_export, onDismiss)
            ImportOutcome.FromNewerAri -> ImportFailedDialog(R.string.settings_import_from_newer, onDismiss)
            is ImportOutcome.Imported -> AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.settings_import_done_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.settings_import_done_secrets))
                        if (outcome.downloads.isNotEmpty()) {
                            val names = outcome.downloads.map { stringResource(it) }
                            Text(stringResource(R.string.settings_import_done_downloads, names.joinToString(", ")))
                        }
                        if (outcome.failedSkills.isNotEmpty()) {
                            Text(
                                stringResource(
                                    R.string.settings_import_done_failed_skills,
                                    outcome.failedSkills.joinToString(", "),
                                ),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDone) { Text(doneLabel) }
                },
            )
        }
    }
}

@Composable
private fun ImportFailedDialog(@StringRes message: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_import_failed_title)) },
        text = { Text(stringResource(message)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}
