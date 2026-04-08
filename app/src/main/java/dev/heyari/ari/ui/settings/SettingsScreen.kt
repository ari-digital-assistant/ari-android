package dev.heyari.ari.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.stt.ModelDownloadState
import dev.heyari.ari.stt.SttModel
import dev.heyari.ari.wakeword.WakeWordModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresh permission state every time the screen comes back to the
    // foreground (e.g. after the user grants an overlay permission in the
    // system settings and returns to Ari).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshPermissions() }

    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshPermissions() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            PermissionsSection(
                permissions = state.permissions,
                onRequestRecordAudio = { recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onRequestNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onOpenFsnSettings = { viewModel.openFsnSettings() },
                onOpenOverlaySettings = { viewModel.openOverlaySettings() },
                onOpenAppSettings = { viewModel.openAppSettings() },
            )

            WakeWordSection(
                wakeWords = state.wakeWords,
                onSelect = viewModel::selectWakeWord,
            )

            ModelsSection(
                models = state.models,
                downloadState = state.download,
                onDownload = viewModel::downloadModel,
                onCancel = viewModel::cancelDownload,
                onDelete = viewModel::deleteModel,
                onSelect = viewModel::selectModel,
            )

            IntegrationSection(
                onSetAsAssistant = viewModel::openDefaultAssistantSettings,
            )
        }
    }
}

@Composable
private fun IntegrationSection(onSetAsAssistant: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("System integration")
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
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun PermissionsSection(
    permissions: PermissionStatus,
    onRequestRecordAudio: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenFsnSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("Permissions")

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
private fun WakeWordSection(
    wakeWords: List<WakeWordOption>,
    onSelect: (WakeWordModel) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("Wake word")
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
private fun ModelsSection(
    models: List<ModelStatus>,
    downloadState: ModelDownloadState,
    onDownload: (SttModel) -> Unit,
    onCancel: () -> Unit,
    onDelete: (SttModel) -> Unit,
    onSelect: (SttModel) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("Speech-to-text model")
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

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.0f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

