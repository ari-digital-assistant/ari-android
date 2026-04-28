package dev.heyari.ari.ui.settings.pages

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.heyari.ari.R
import dev.heyari.ari.models.ModelTarget
import dev.heyari.ari.models.ModelUpdate
import dev.heyari.ari.ui.settings.AutoUpdateState
import dev.heyari.ari.ui.settings.AutoUpdateViewModel
import dev.heyari.ari.ui.settings.InstalledModelRow
import dev.heyari.ari.ui.settings.components.SettingsScaffold
import java.time.Duration
import java.time.Instant
import java.util.Locale

@Composable
fun AutoUpdateSettingsPage(
    onBack: () -> Unit,
    viewModel: AutoUpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.toast) {
        val toast = state.toast ?: return@LaunchedEffect
        Toast.makeText(context, toast, Toast.LENGTH_LONG).show()
        viewModel.consumeToast()
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_category_auto_update),
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
            MasterToggleCard(
                enabled = state.masterEnabled,
                allowMetered = state.allowMetered,
                lastCheckedAt = state.lastCheckedAt,
                checking = state.checking,
                onSetEnabled = viewModel::setMasterEnabled,
                onSetAllowMetered = viewModel::setAllowMetered,
                onCheckNow = viewModel::checkNow,
            )

            if (state.pendingUpdates.isNotEmpty()) {
                SectionLabel(text = stringResource(R.string.auto_update_section_pending))
                state.pendingUpdates.forEach { update ->
                    PendingUpdateCard(
                        update = update,
                        applying = state.applyingTargetKey == update.target.key,
                        onApply = { viewModel.applyUpdate(update) },
                        onSkip = { viewModel.skipUpdate(update) },
                    )
                }
            }

            SectionLabel(text = stringResource(R.string.auto_update_section_installed))
            if (state.installedModels.isEmpty()) {
                Text(
                    text = stringResource(R.string.auto_update_no_models_installed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.installedModels.forEach { row ->
                    InstalledModelCard(row)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = viewModel::resetSkippedVersions) {
                    Text(stringResource(R.string.auto_update_reset_skipped))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MasterToggleCard(
    enabled: Boolean,
    allowMetered: Boolean,
    lastCheckedAt: Instant?,
    checking: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onSetAllowMetered: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.auto_update_master_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.auto_update_master_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = enabled, onCheckedChange = onSetEnabled)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.auto_update_metered_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.auto_update_metered_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = allowMetered,
                    onCheckedChange = onSetAllowMetered,
                    enabled = enabled,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatLastChecked(lastCheckedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onCheckNow, enabled = enabled && !checking) {
                    Text(
                        if (checking) stringResource(R.string.auto_update_checking)
                        else stringResource(R.string.auto_update_check_now),
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingUpdateCard(
    update: ModelUpdate,
    applying: Boolean,
    onApply: () -> Unit,
    onSkip: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = update.target.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(
                    R.string.auto_update_pending_versions,
                    update.installedVersion,
                    update.availableVersion,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.auto_update_pending_size,
                    formatBytes(update.sizeBytes),
                    update.manifest.releasedAt ?: "—",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onSkip, enabled = !applying) {
                    Text(stringResource(R.string.auto_update_skip))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onApply, enabled = !applying) {
                    Text(
                        if (applying) stringResource(R.string.auto_update_applying)
                        else stringResource(R.string.auto_update_apply),
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledModelCard(row: InstalledModelRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = row.target.displayName,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.auto_update_installed_version, row.installedVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun formatLastChecked(instant: Instant?): String {
    if (instant == null) return stringResource(R.string.auto_update_last_checked_never)
    val ago = Duration.between(instant, Instant.now())
    return when {
        ago.toMinutes() < 1 -> stringResource(R.string.auto_update_last_checked_just_now)
        ago.toHours() < 1 -> stringResource(R.string.auto_update_last_checked_minutes, ago.toMinutes().toInt())
        ago.toDays() < 1 -> stringResource(R.string.auto_update_last_checked_hours, ago.toHours().toInt())
        else -> stringResource(R.string.auto_update_last_checked_days, ago.toDays().toInt())
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.0f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
