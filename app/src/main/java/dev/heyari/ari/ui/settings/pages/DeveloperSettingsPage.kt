package dev.heyari.ari.ui.settings.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.contrib.DeleteOutcome
import dev.heyari.ari.ui.settings.RecordingsViewModel
import dev.heyari.ari.ui.settings.components.SettingsScaffold
import dev.heyari.ari.ui.theme.LocalAriSemanticColors

/**
 * Everything to do with keeping and contributing recordings, in one place.
 *
 * These switches are the only ones in the app that can put microphone audio on
 * somebody else's computer, so they live together rather than scattered across
 * the feature pages that produce the audio.
 */
@Composable
fun DeveloperSettingsPage(
    onBack: () -> Unit,
    viewModel: RecordingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Resolved during composition rather than inside the export lambdas:
    // LocalContext reads don't invalidate on a Configuration change, so a
    // getString() there would hand out a stale title after a language switch.
    val wakeExportChooserTitle = stringResource(R.string.settings_wake_capture_export_chooser)
    val utteranceExportChooserTitle = stringResource(R.string.settings_utterance_capture_export_chooser)
    val codeCopied = stringResource(R.string.settings_contributor_code_copied)
    val deleteDone = stringResource(R.string.settings_delete_shared_done)
    val deleteFailed = stringResource(R.string.settings_delete_shared_failed)

    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var restoringCode by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.deleteOutcome) {
        val outcome = state.deleteOutcome ?: return@LaunchedEffect
        val message = when (outcome) {
            is DeleteOutcome.Erased -> deleteDone
            is DeleteOutcome.Refused -> outcome.reason
            is DeleteOutcome.Failed -> deleteFailed
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        viewModel.consumeDeleteOutcome()
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_category_developer),
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_recordings_heading),
                style = MaterialTheme.typography.titleLarge,
            )

            WhatToRecordSection(
                keep = state.keep,
                onKeepWake = viewModel::setKeepWake,
                onKeepFalseTrigger = viewModel::setKeepFalseTrigger,
                onKeepCommand = viewModel::setKeepCommand,
                onKeepEverything = viewModel::setKeepEverything,
            )

            HorizontalDivider()

            CaptureStorageSection(
                title = stringResource(R.string.settings_storage_wake_title),
                stats = state.wakeCaptureStats,
                onExport = {
                    viewModel.wakeCaptureShareIntent()?.let { intent ->
                        context.startActivity(Intent.createChooser(intent, wakeExportChooserTitle))
                    }
                },
                onClear = viewModel::clearWakeCaptures,
            )
            CaptureStorageSection(
                title = stringResource(R.string.settings_storage_command_title),
                stats = state.utteranceCaptureStats,
                onExport = {
                    viewModel.utteranceCaptureShareIntent()?.let { intent ->
                        context.startActivity(Intent.createChooser(intent, utteranceExportChooserTitle))
                    }
                },
                onClear = viewModel::clearUtteranceCaptures,
            )

            HorizontalDivider()

            WhatToShareSection(
                keep = state.keep,
                share = state.share,
                onShareWake = viewModel::setShareWake,
                onShareFalseTrigger = viewModel::setShareFalseTrigger,
                onShareCommand = viewModel::setShareCommand,
                onShareEverything = viewModel::setShareEverything,
            )

            TextButton(onClick = { restoringCode = true }) {
                Text(stringResource(R.string.settings_contributor_code_restore))
            }

            // The code is the only way to ask for deletion from a phone that
            // has been wiped, so it appears the moment sharing is switched on
            // rather than after the first upload.
            state.contributorId?.let { id ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.settings_contributor_code_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_contributor_code_blurb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = id,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText(null, id))
                            Toast.makeText(context, codeCopied, Toast.LENGTH_SHORT).show()
                        }) {
                            Text(stringResource(R.string.settings_contributor_code_copy))
                        }
                    }
                }
            }

            if (state.hasSharedRecordings) {
                OutlinedButton(
                    onClick = { confirmingDelete = true },
                    enabled = !state.deleting,
                ) {
                    Text(
                        text = stringResource(R.string.settings_delete_shared_title),
                        color = LocalAriSemanticColors.current.danger,
                    )
                }
            }
        }
    }

    if (restoringCode) {
        RestoreCodeDialog(
            onDismiss = { restoringCode = false },
            onAdopt = viewModel::adoptContributorCode,
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.settings_delete_shared_title)) },
            text = { Text(stringResource(R.string.settings_delete_shared_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.deleteSharedData()
                }) {
                    Text(
                        text = stringResource(R.string.settings_delete_shared_action),
                        color = LocalAriSemanticColors.current.danger,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * Puts a contributor code from a previous install back.
 *
 * The dialog owns the retry rather than the page: a mistyped code is a typo to
 * correct in place, not a reason to close everything and start again.
 */
@Composable
private fun RestoreCodeDialog(
    onDismiss: () -> Unit,
    onAdopt: (String, (Boolean) -> Unit) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var rejected by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_contributor_code_restore_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_contributor_code_restore_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        rejected = false
                    },
                    label = { Text(stringResource(R.string.settings_contributor_code_title)) },
                    singleLine = true,
                    isError = rejected,
                    supportingText = if (rejected) {
                        { Text(stringResource(R.string.settings_contributor_code_restore_invalid)) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdopt(code) { ok -> if (ok) onDismiss() else rejected = true } },
                enabled = code.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
