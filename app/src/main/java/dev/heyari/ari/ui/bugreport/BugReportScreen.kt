package dev.heyari.ari.ui.bugreport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.bugreport.AttachmentKind
import dev.heyari.ari.bugreport.AttachmentOffer
import dev.heyari.ari.ui.settings.components.SettingsScaffold

/**
 * The whole reporting flow behind one route: fill it in, see exactly what is
 * about to be sent, send it.
 *
 * Review is a step rather than a dialog because the promise it keeps — that a
 * reporter knows precisely what leaves their phone — is not one a summary can
 * keep. It shows the issue body and the file list themselves.
 */
@Composable
fun BugReportScreen(
    onClose: () -> Unit,
    onOpenIssue: (String) -> Unit,
    crashTrace: String? = null,
    viewModel: BugReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(crashTrace) { viewModel.start(crashTrace, screenshotPng = null) }

    val title = when (state.step) {
        BugReportStep.EDITING -> stringResource(R.string.bug_report_title)
        BugReportStep.REVIEWING, BugReportStep.SENDING ->
            stringResource(R.string.bug_report_review_title)
        BugReportStep.SENT -> stringResource(R.string.bug_report_sent_title)
    }

    SettingsScaffold(
        title = title,
        onBack = {
            if (state.step == BugReportStep.REVIEWING) viewModel.backToEditing() else onClose()
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (state.step) {
                BugReportStep.EDITING -> EditingStep(state, viewModel)
                BugReportStep.REVIEWING, BugReportStep.SENDING -> ReviewStep(state, viewModel)
                BugReportStep.SENT -> SentStep(state, onOpenIssue, onClose)
            }
        }
    }
}

@Composable
private fun EditingStep(state: BugReportUiState, viewModel: BugReportViewModel) {
    Column {
        OutlinedTextField(
            value = state.description,
            onValueChange = viewModel::setDescription,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.bug_report_what_happened)) },
            minLines = 3,
        )
        Text(
            text = stringResource(R.string.bug_report_public_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
    }

    Column {
        OutlinedTextField(
            value = state.privateNote,
            onValueChange = viewModel::setPrivateNote,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.bug_report_private_label)) },
            placeholder = { Text(stringResource(R.string.bug_report_optional)) },
            minLines = 2,
        )
        Text(
            text = stringResource(R.string.bug_report_private_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
    }

    HorizontalDivider()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.bug_report_always_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.bug_report_always_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.bug_report_files_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.bug_report_files_blurb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    ConsentCard(state.consented, viewModel::setConsent)

    // Untouchable until consent is given, and visibly so. The alternative —
    // letting people tick files and only then asking — collects choices we
    // have no right to act on yet.
    state.offers.forEach { offer ->
        AttachmentRow(
            offer = offer,
            checked = offer.kind in state.selected,
            enabled = state.consented,
            onToggle = { viewModel.toggle(offer.kind) },
        )
    }

    Spacer(Modifier.height(8.dp))

    Button(
        onClick = viewModel::review,
        enabled = state.canSend,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.bug_report_review_action))
    }
}

@Composable
private fun ConsentCard(consented: Boolean, onChange: (Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .toggleable(value = consented, role = Role.Checkbox, onValueChange = onChange),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(checked = consented, onCheckedChange = null)
                Text(
                    text = stringResource(R.string.bug_report_consent),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = stringResource(R.string.bug_report_consent_blurb),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun AttachmentRow(
    offer: AttachmentOffer,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(offer.kind.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = offer.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReviewStep(state: BugReportUiState, viewModel: BugReportViewModel) {
    Text(
        text = stringResource(R.string.bug_report_review_blurb),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SectionCard(
        title = stringResource(R.string.bug_report_public_section),
        caption = stringResource(R.string.bug_report_public_section_blurb),
        border = MaterialTheme.colorScheme.error,
    ) {
        Text(state.description.trim(), style = MaterialTheme.typography.bodyMedium)
    }

    SectionCard(
        title = stringResource(R.string.bug_report_private_section),
        caption = stringResource(R.string.bug_report_private_section_blurb),
        border = MaterialTheme.colorScheme.outlineVariant,
    ) {
        val sending = state.offers.filter { it.kind in state.sending }
        if (sending.isEmpty() && state.privateNote.isBlank()) {
            Text(
                text = stringResource(R.string.bug_report_nothing_private),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.privateNote.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.bug_report_your_private_note),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                sending.forEach { offer ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = stringResource(offer.kind.labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = formatBytes(offer.bytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    when (val error = state.error) {
        is SendError.Rejected -> ErrorText(
            stringResource(R.string.bug_report_error_rejected, error.reason),
        )
        SendError.Unreachable -> ErrorText(stringResource(R.string.bug_report_error_offline))
        null -> Unit
    }

    Spacer(Modifier.height(4.dp))

    if (state.step == BugReportStep.SENDING) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(32.dp))
        }
    } else {
        Button(
            onClick = viewModel::send,
            enabled = state.canSend,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.bug_report_send_action))
        }
    }
}

@Composable
private fun SentStep(
    state: BugReportUiState,
    onOpenIssue: (String) -> Unit,
    onClose: () -> Unit,
) {
    val filed = state.filed ?: return
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.bug_report_thanks),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(R.string.bug_report_thanks_blurb),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.bug_report_issue_number, filed.issueNumber),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
    Button(
        onClick = { onOpenIssue(filed.issueUrl) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.bug_report_open_on_github))
    }
    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.bug_report_done))
    }
    Text(
        text = stringResource(R.string.bug_report_retention_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionCard(
    title: String,
    caption: String,
    border: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

private val AttachmentKind.labelRes: Int
    get() = when (this) {
        AttachmentKind.LOGCAT -> R.string.bug_report_file_logcat
        AttachmentKind.SCREENSHOT -> R.string.bug_report_file_screenshot
        AttachmentKind.CONVERSATION -> R.string.bug_report_file_conversation
        AttachmentKind.COMMANDS -> R.string.bug_report_file_commands
        AttachmentKind.WAKE_AUDIO -> R.string.bug_report_file_wake_audio
        AttachmentKind.ALL_AUDIO -> R.string.bug_report_file_all_audio
    }

/**
 * "3 recordings · 1.1 MB". Naming the count and the size is the point: a
 * reporter deciding whether to hand over their own voice deserves to know how
 * much of it there is.
 */
@Composable
private fun AttachmentOffer.summary(): String = when (kind) {
    AttachmentKind.LOGCAT -> stringResource(R.string.bug_report_file_logcat_note)
    AttachmentKind.SCREENSHOT -> stringResource(R.string.bug_report_file_screenshot_note)
    AttachmentKind.CONVERSATION ->
        pluralStringResource(R.plurals.bug_report_turns, fileCount, fileCount)
    else -> stringResource(
        R.string.bug_report_file_count_size,
        pluralStringResource(R.plurals.bug_report_recordings, fileCount, fileCount),
        formatBytes(bytes),
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
