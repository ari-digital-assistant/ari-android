package dev.heyari.ari.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R
import dev.heyari.ari.reporting.ContentReport
import dev.heyari.ari.reporting.ReportCategory
import dev.heyari.ari.reporting.ReportKind

/**
 * Confirmation dialog for reporting something Ari said.
 *
 * The whole point is that the user sees exactly what leaves the device before
 * it does. The reported text is shown verbatim, their own preceding words are
 * shown too and can be withheld, and nothing is sent until they tap send —
 * which is also why there is no "reported!" toast promising more than we know.
 *
 * [prompt] is what the user said just before, or null when the reported message
 * opened the conversation.
 */
@Composable
fun ReportDialog(
    reportedText: String,
    prompt: String?,
    skillId: String?,
    kind: ReportKind = ReportKind.RESPONSE,
    onDismiss: () -> Unit,
    onSend: (ContentReport) -> Unit,
) {
    var category by remember { mutableStateOf(ReportCategory.OFFENSIVE) }
    var note by remember { mutableStateOf("") }
    var includePrompt by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (kind == ReportKind.SKILL) R.string.report_skill_title
                    else R.string.report_title
                )
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.report_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))

                for (option in ReportCategory.entries) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = category == option,
                                onClick = { category = option },
                            ),
                    ) {
                        RadioButton(selected = category == option, onClick = { category = option })
                        Text(stringResource(labelFor(option)))
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.report_note_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        if (kind == ReportKind.SKILL) R.string.report_skill_what_is_sent
                        else R.string.report_what_is_sent
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(4.dp))
                // Capped and scrollable: a long answer must not push the send
                // button off the screen, which would leave no way to finish.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                ) {
                    Text(
                        text = reportedText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth(),
                    )
                }

                if (prompt != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = includePrompt,
                                onClick = { includePrompt = !includePrompt },
                            ),
                    ) {
                        Checkbox(
                            checked = includePrompt,
                            onCheckedChange = { includePrompt = it },
                        )
                        Text(
                            text = stringResource(R.string.report_include_prompt, prompt),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSend(
                    ContentReport(
                        kind = kind,
                        category = category,
                        text = reportedText,
                        prompt = prompt?.takeIf { includePrompt },
                        note = note.takeIf { it.isNotBlank() },
                        skillId = skillId,
                    )
                )
            }) {
                Text(stringResource(R.string.report_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.report_cancel)) }
        },
    )
}

private fun labelFor(category: ReportCategory): Int = when (category) {
    ReportCategory.OFFENSIVE -> R.string.report_category_offensive
    ReportCategory.HARMFUL -> R.string.report_category_harmful
    ReportCategory.WRONG -> R.string.report_category_wrong
    ReportCategory.OTHER -> R.string.report_category_other
}
