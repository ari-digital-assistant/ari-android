package dev.heyari.ari.ui.settings.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.BuildConfig
import dev.heyari.ari.diagnostics.DeviceDiagnostics
import dev.heyari.ari.diagnostics.DiagnosticsViewModel
import dev.heyari.ari.ui.formatBytes
import dev.heyari.ari.ui.theme.LocalAriSemanticColors
import dev.heyari.ari.R
import dev.heyari.ari.ui.settings.components.SettingsScaffold

/**
 * Tester tools: what this device is and what Ari runs on it, making a
 * recording on purpose, breaking the app on purpose, and taking back a
 * report already sent.
 *
 * The passive capture toggles used to live here too. They moved to Settings >
 * Developer when sharing was added — a switch that can put audio on somebody
 * else's computer belongs next to the one that decides whether it may, not
 * next to a button that crashes the app.
 */
@Composable
fun DebugSettingsPage(
    onBack: () -> Unit,
    onOpenMyReports: () -> Unit,
    onOpenWakeSamples: () -> Unit,
    diagnostics: DiagnosticsViewModel = hiltViewModel(),
) {
    val facts by diagnostics.facts.collectAsStateWithLifecycle()
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
            // First, because it is what a tester is asked for when something
            // goes wrong: which phone, which chip, which CPU build.
            DeviceInfoSection(facts)
            HorizontalDivider()

            // Deliberate recording rather than passive capture, so it gets a
            // page of its own: a session has a person in it who needs telling
            // when to speak and where to stand.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenWakeSamples)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_wake_samples_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_wake_samples_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // A deliberate crash, so the crash prompt and the trace it carries
            // can be exercised on purpose rather than waited for. Testing
            // builds only — the flag that gates the reporter gates this too,
            // because a button that kills the app has no business in a release.
            if (BuildConfig.ARI_TESTING) {
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            throw IllegalStateException(
                                "Deliberate crash from Settings > Debug, to test the reporter"
                            )
                        }
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_crash_now_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = LocalAriSemanticColors.current.danger,
                    )
                    Text(
                        text = stringResource(R.string.settings_crash_now_blurb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

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

/** The device facts a bug report carries, with a button to copy them. */
@Composable
private fun DeviceInfoSection(facts: DeviceDiagnostics?) {
    val context = LocalContext.current
    val copied = stringResource(R.string.settings_device_info_copied)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_device_info_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (facts != null) {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Ari device details", facts.toText()))
                    // Android 13 and up confirm a copy themselves.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(stringResource(R.string.settings_device_info_copy))
                }
            }
        }
        if (facts == null) {
            Text(
                text = stringResource(R.string.settings_device_info_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val none = stringResource(R.string.settings_device_info_none)
            InfoRow(R.string.settings_device_info_device, facts.device)
            facts.chip?.let { InfoRow(R.string.settings_device_info_chip, it) }
            InfoRow(R.string.settings_device_info_android, facts.android)
            facts.memoryTotalMb?.let { InfoRow(R.string.settings_device_info_memory, formatBytes(it * 1_000_000)) }
            InfoRow(
                R.string.settings_device_info_cpu,
                stringResource(R.string.settings_device_info_cpu_value, facts.cpuCores, facts.abi),
            )
            InfoRow(R.string.settings_device_info_cpu_features, facts.cpuFeatures.joinToString().ifEmpty { none })
            InfoRow(R.string.settings_device_info_ari, facts.appVersion)
            InfoRow(R.string.settings_device_info_engine, facts.engineVersion ?: none)
            InfoRow(R.string.settings_device_info_assistant, facts.assistant ?: none)
            InfoRow(R.string.settings_device_info_llm, facts.llmSummary ?: none)
            InfoRow(R.string.settings_device_info_speech, facts.speech)
        }
    }
}

@Composable
private fun InfoRow(@StringRes label: Int, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.6f),
        )
    }
}
