package dev.heyari.ari.ui.settings.pages

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.components.SettingsScaffold

/**
 * Debug-only capture settings, gathered in one place so the privacy-sensitive
 * toggles are never scattered across feature pages: the wake false-trigger
 * capture, the spoken-command capture, and the keep-everything firehose that
 * implies both and additionally records every ACCEPTED wake.
 */
@Composable
fun DebugSettingsPage(
    onBack: () -> Unit,
    onOpenMyReports: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Resolved during composition, not inside the export lambdas: LocalContext
    // reads don't invalidate on a Configuration change, so getString() there
    // would hand out a stale title after a language switch.
    val wakeExportChooserTitle = stringResource(R.string.settings_wake_capture_export_chooser)
    val utteranceExportChooserTitle = stringResource(R.string.settings_utterance_capture_export_chooser)

    SettingsScaffold(
        title = stringResource(R.string.settings_category_debug),
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
            // The firehose first: it supersedes the two feature toggles below,
            // so it reads as the headline decision.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_keep_everything_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Switch(
                        checked = state.keepEverythingAudio,
                        onCheckedChange = viewModel::setKeepEverythingAudio,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_keep_everything_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AudioCaptureSection(
                title = stringResource(R.string.settings_wake_capture_title),
                blurb = stringResource(R.string.settings_wake_capture_blurb),
                enabled = state.keepFalseTriggerAudio,
                stats = state.wakeCaptureStats,
                onToggle = viewModel::setKeepFalseTriggerAudio,
                onExport = {
                    viewModel.wakeCaptureShareIntent()?.let { intent ->
                        context.startActivity(
                            Intent.createChooser(intent, wakeExportChooserTitle)
                        )
                    }
                },
                onClear = viewModel::clearWakeCaptures,
            )
            AudioCaptureSection(
                title = stringResource(R.string.settings_utterance_capture_title),
                blurb = stringResource(R.string.settings_utterance_capture_blurb),
                enabled = state.keepUtteranceAudio,
                stats = state.utteranceCaptureStats,
                onToggle = viewModel::setKeepUtteranceAudio,
                onExport = {
                    viewModel.utteranceCaptureShareIntent()?.let { intent ->
                        context.startActivity(
                            Intent.createChooser(intent, utteranceExportChooserTitle)
                        )
                    }
                },
                onClear = viewModel::clearUtteranceCaptures,
            )

            // Last, because it is where a tester goes to undo something rather
            // than to configure anything.
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenMyReports)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_my_reports_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_my_reports_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
