package dev.heyari.ari.ui.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.heyari.ari.llm.LlmDownloadState
import dev.heyari.ari.llm.LlmModel
import dev.heyari.ari.stt.ModelDownloadState
import dev.heyari.ari.stt.SttModel
import dev.heyari.ari.ui.settings.LlmModelStatus
import dev.heyari.ari.ui.settings.ModelStatus
import dev.heyari.ari.ui.settings.PermissionStatus
import dev.heyari.ari.ui.settings.WakeWordOption
import dev.heyari.ari.wakeword.WakeWordModel
import dev.heyari.ari.wakeword.WakeWordSensitivity
import java.util.Locale

@Composable
internal fun PermissionsSection(
    permissions: PermissionStatus,
    onRequestRecordAudio: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenFsnSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PermissionRow(
            label = "Microphone",
            description = "Required for wake word and speech input",
            granted = permissions.recordAudio,
            required = true,
            actionLabel = if (permissions.recordAudio) "Granted" else "Grant",
            onAction = if (permissions.recordAudio) onOpenAppSettings else onRequestRecordAudio,
        )

        PermissionRow(
            label = "Notifications",
            description = "Required to show wake word listening status",
            granted = permissions.postNotifications,
            required = true,
            actionLabel = if (permissions.postNotifications) "Granted" else "Grant",
            onAction = if (permissions.postNotifications) onOpenAppSettings else onRequestNotifications,
        )

        PermissionRow(
            label = "Lock screen wake word",
            description = "Required for the wake word to keep working when your phone is locked — like how phone calls appear on the lock screen. Android calls this \"Display over other apps\", but Ari does not draw on top of your other apps. It only uses this permission to open its own voice screen when you say the wake word. Without it, the wake word will only work once per session.",
            granted = permissions.systemAlertWindow,
            required = true,
            actionLabel = if (permissions.systemAlertWindow) "Granted" else "Open Android settings",
            onAction = onOpenOverlaySettings,
        )

        PermissionRow(
            label = "Full-screen notifications",
            description = "Optional. Legacy fallback path — not used by Ari's current wake word flow, but kept available in case Android tightens overlay rules in future.",
            granted = permissions.fullScreenIntent,
            required = false,
            actionLabel = if (permissions.fullScreenIntent) "Granted" else "Open settings",
            onAction = onOpenFsnSettings,
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    description: String,
    granted: Boolean,
    required: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    contentDescription = null,
                    tint = if (granted) Color(0xFF2E7D32) else if (required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (required) {
                    Text(
                        text = "REQUIRED",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onAction, enabled = !granted || actionLabel != "Granted") {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
internal fun WakeWordSection(
    wakeWords: List<WakeWordOption>,
    onSelect: (WakeWordModel) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "The phrase Ari listens for. \"Hey Jarvis\" is the original microWakeWord model and is included as a fallback.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        wakeWords.forEach { option ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (option.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = option.active,
                        onClick = { onSelect(option.model) },
                    )
                    Text(
                        text = option.model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun WakeWordSensitivitySection(
    current: WakeWordSensitivity,
    onSelect: (WakeWordSensitivity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Sensitivity — how eagerly Ari reacts. If she wakes when she shouldn't, lower this. If she misses you, raise it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        WakeWordSensitivity.entries.forEach { option ->
            val active = option == current
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = active,
                        onClick = { onSelect(option) },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = option.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModelsSection(
    models: List<ModelStatus>,
    downloadState: ModelDownloadState,
    onDownload: (SttModel) -> Unit,
    onCancel: () -> Unit,
    onDelete: (SttModel) -> Unit,
    onSelect: (SttModel) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Choose a voice recognition model. Larger models are more accurate but use more storage and RAM.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        models.forEach { status ->
            ModelRow(
                status = status,
                downloadState = downloadState,
                onDownload = { onDownload(status.model) },
                onCancel = onCancel,
                onDelete = { onDelete(status.model) },
                onSelect = { onSelect(status.model) },
            )
        }
    }
}

@Composable
private fun ModelRow(
    status: ModelStatus,
    downloadState: ModelDownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    val isDownloadingThis = downloadState is ModelDownloadState.Downloading && downloadState.modelId == status.model.id
    val downloadFailed = downloadState is ModelDownloadState.Failed && downloadState.modelId == status.model.id

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status.downloaded) {
                    RadioButton(
                        selected = status.active,
                        onClick = onSelect,
                    )
                } else {
                    Spacer(Modifier.width(48.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = status.model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = status.model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when {
                isDownloadingThis -> {
                    val dl = downloadState as ModelDownloadState.Downloading
                    val progress = if (dl.totalBytes > 0) dl.bytesSoFar.toFloat() / dl.totalBytes.toFloat() else 0f
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${formatBytes(dl.bytesSoFar)} / ${formatBytes(dl.totalBytes)} (${String.format(Locale.US, "%.0f", progress * 100)}%)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                status.downloaded -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                }
                else -> {
                    if (downloadFailed) {
                        Text(
                            text = "Last download failed: ${(downloadState as ModelDownloadState.Failed).error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(onClick = onDownload) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Download ${formatBytes(status.model.totalBytes)}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StartOnBootSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Start listening on boot",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "When your device boots, Ari will start listening for the wake word automatically. Uses a bit more battery since the microphone is always on — leave it off if you'd rather start Ari manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                )
            }
        }
    }
}

@Composable
internal fun IntegrationSection(onSetAsAssistant: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Default digital assistant",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Set Ari as your default digital assistant so it can be invoked with the assist gesture (long-press home button or power button).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onSetAsAssistant) {
                    Text("Open settings")
                }
            }
        }
    }
}

@Composable
internal fun LlmModelsSection(
    models: List<LlmModelStatus>,
    downloadState: LlmDownloadState,
    noneActive: Boolean,
    onDownload: (LlmModel) -> Unit,
    onCancel: () -> Unit,
    onDelete: (LlmModel) -> Unit,
    onSelect: (LlmModel) -> Unit,
    onSelectNone: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Choose an on-device language model. When enabled, Ari can answer general questions and reroute misheard commands to the right skill. Larger models give better answers but use more storage and RAM.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        // "None" option
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (noneActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = noneActive,
                    onClick = onSelectNone,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "None",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "No on-device LLM. Ari only answers via matched skills.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Model tiers
        models.forEach { status ->
            LlmModelRow(
                status = status,
                downloadState = downloadState,
                onDownload = { onDownload(status.model) },
                onCancel = onCancel,
                onDelete = { onDelete(status.model) },
                onSelect = { onSelect(status.model) },
            )
        }
    }
}

@Composable
private fun LlmModelRow(
    status: LlmModelStatus,
    downloadState: LlmDownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    val isDownloadingThis = downloadState is LlmDownloadState.Downloading && downloadState.modelId == status.model.id
    val downloadFailed = downloadState is LlmDownloadState.Failed && downloadState.modelId == status.model.id

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status.downloaded) {
                    RadioButton(
                        selected = status.active,
                        onClick = onSelect,
                    )
                } else {
                    Spacer(Modifier.width(48.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = status.model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = status.model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when {
                isDownloadingThis -> {
                    val dl = downloadState as LlmDownloadState.Downloading
                    val progress = if (dl.totalBytes > 0) dl.bytesSoFar.toFloat() / dl.totalBytes.toFloat() else 0f
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${formatBytes(dl.bytesSoFar)} / ${formatBytes(dl.totalBytes)} (${String.format(Locale.US, "%.0f", progress * 100)}%)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                status.downloaded -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                }
                else -> {
                    if (downloadFailed) {
                        Text(
                            text = "Last download failed: ${(downloadState as LlmDownloadState.Failed).error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(onClick = onDownload) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Download ${formatBytes(status.model.totalBytes)}")
                        }
                    }
                }
            }
        }
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
