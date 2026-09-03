package dev.heyari.ari.ui.bugreport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.bugreport.FiledReportRecord
import dev.heyari.ari.ui.settings.components.SettingsScaffold
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Settings › Debug › My Reports.
 *
 * The whole of a tester's ability to change their mind, which is why the
 * footnote says plainly that it lives on this phone only. An expired report
 * keeps its row but loses its Withdraw button: its files are already gone, and
 * the issue was never the reporter's to remove.
 */
@Composable
fun MyReportsPage(
    onBack: () -> Unit,
    onOpenIssue: (String) -> Unit,
    viewModel: MyReportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.my_reports_title),
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.reports.isEmpty()) {
                Text(
                    text = stringResource(R.string.my_reports_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.reports.forEach { report ->
                ReportRow(
                    report = report,
                    busy = state.withdrawing == report.reportId,
                    onOpen = { onOpenIssue(report.issueUrl) },
                    onWithdraw = { viewModel.confirm(report) },
                )
            }

            if (state.failed) {
                Text(
                    text = stringResource(R.string.my_reports_withdraw_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.reports.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.my_reports_footnote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    state.confirming?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.my_reports_confirm_title)) },
            text = { Text(stringResource(R.string.my_reports_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.withdraw(report) }) {
                    Text(stringResource(R.string.my_reports_confirm_withdraw))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismiss) {
                    Text(stringResource(R.string.my_reports_confirm_keep))
                }
            },
        )
    }
}

@Composable
private fun ReportRow(
    report: FiledReportRecord,
    busy: Boolean,
    onOpen: () -> Unit,
    onWithdraw: () -> Unit,
) {
    val expired = report.isExpired(System.currentTimeMillis())
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = report.title.ifBlank { stringResource(R.string.my_reports_untitled) },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = if (expired) {
                    stringResource(
                        R.string.my_reports_meta_expired,
                        report.issueNumber,
                        formatDate(report.filedAtMillis),
                    )
                } else {
                    stringResource(
                        R.string.my_reports_meta,
                        report.issueNumber,
                        formatDate(report.filedAtMillis),
                        formatDate(report.expiresAtMillis),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onOpen, enabled = !busy) {
                    Text(stringResource(R.string.my_reports_view))
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp).padding(top = 8.dp))
                } else if (!expired) {
                    TextButton(onClick = onWithdraw) {
                        Text(stringResource(R.string.my_reports_withdraw))
                    }
                }
            }
        }
    }
}

private val DATE: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

private fun formatDate(millis: Long): String = DATE.format(Instant.ofEpochMilli(millis))
