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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R
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
    onRequestLocation: () -> Unit,
    onOpenFsnSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PermissionRow(
            label = stringResource(R.string.permission_microphone_label),
            description = stringResource(R.string.permission_microphone_description),
            granted = permissions.recordAudio,
            required = true,
            actionLabel = stringResource(
                if (permissions.recordAudio) R.string.permission_status_granted
                else R.string.permission_status_grant
            ),
            onAction = if (permissions.recordAudio) onOpenAppSettings else onRequestRecordAudio,
        )

        PermissionRow(
            label = stringResource(R.string.permission_notifications_label),
            description = stringResource(R.string.permission_notifications_description),
            granted = permissions.postNotifications,
            required = true,
            actionLabel = stringResource(
                if (permissions.postNotifications) R.string.permission_status_granted
                else R.string.permission_status_grant
            ),
            onAction = if (permissions.postNotifications) onOpenAppSettings else onRequestNotifications,
        )

        PermissionRow(
            label = stringResource(R.string.permission_location_label),
            description = stringResource(R.string.permission_location_description),
            granted = permissions.location,
            required = false,
            actionLabel = stringResource(
                if (permissions.location) R.string.permission_status_granted
                else R.string.permission_status_grant
            ),
            onAction = if (permissions.location) onOpenAppSettings else onRequestLocation,
        )

        PermissionRow(
            label = stringResource(R.string.permission_lockscreen_label),
            description = stringResource(R.string.permission_lockscreen_description),
            granted = permissions.systemAlertWindow,
            required = true,
            actionLabel = stringResource(
                if (permissions.systemAlertWindow) R.string.permission_status_granted
                else R.string.permission_status_open_android_settings
            ),
            onAction = onOpenOverlaySettings,
        )

        PermissionRow(
            label = stringResource(R.string.permission_fsn_label),
            description = stringResource(R.string.permission_fsn_description),
            granted = permissions.fullScreenIntent,
            required = false,
            actionLabel = stringResource(
                if (permissions.fullScreenIntent) R.string.permission_status_granted
                else R.string.action_open_settings
            ),
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
                    tint = if (granted) Color(0xFF2E7D32) else if (required) Color(0xFFF57C00) else MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (required) R.string.permission_chip_recommended
                        else R.string.permission_chip_optional
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (required) Color(0xFFF57C00) else MaterialTheme.colorScheme.outline,
                )
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
                // Disable when granted; the only "label != something to do"
                // case is the localised "Granted" stamp, and we now drive
                // disable purely off the boolean to keep this branch
                // language-independent.
                TextButton(onClick = onAction, enabled = !granted) {
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
            text = stringResource(R.string.settings_wakeword_blurb),
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
            text = stringResource(R.string.settings_wakeword_sensitivity_blurb),
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
                            text = stringResource(option.displayNameRes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(option.descriptionRes),
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
            text = stringResource(R.string.settings_stt_models_blurb),
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
                    // Until the worker reports a total size we don't know how
                    // far along we are (just-tapped / connecting / enqueued),
                    // so show an indeterminate bar rather than a frozen 0%.
                    if (dl.totalBytes > 0) {
                        val progress = (dl.bytesSoFar.toFloat() / dl.totalBytes.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (dl.totalBytes > 0) {
                            val pct = dl.bytesSoFar.toFloat() / dl.totalBytes.toFloat() * 100
                            Text(
                                text = "${formatBytes(dl.bytesSoFar)} / ${formatBytes(dl.totalBytes)} (${String.format(Locale.US, "%.0f", pct)}%)",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
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
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
                else -> {
                    if (downloadFailed) {
                        Text(
                            text = stringResource(R.string.settings_last_download_failed, (downloadState as ModelDownloadState.Failed).error),
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
                            Text(stringResource(R.string.download_button_with_size, formatBytes(status.model.totalBytes)))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Per-locale display data for the post-onboarding language picker.
 * Keep in lockstep with onboarding's `LANGUAGE_OPTIONS` and
 * `SupportedLocales.codes`. `displayName` is the language's self-name
 * — never translated, since a user picking a language they don't yet
 * speak needs to recognise it in its own form.
 */
private data class LanguageOption(val code: String, val displayName: String)

private val LANGUAGE_OPTIONS: List<LanguageOption> = listOf(
    LanguageOption("en", "English"),
    LanguageOption("it", "Italiano"),
)

/**
 * Lets the user change Ari's language after onboarding. Switching here
 * fans out the same way the onboarding picker does:
 * SettingsRepository.activeLocale → engine (via FfiLocaleProvider) →
 * Android per-app locale (mirrored on next process start) → all
 * composables observing `state.activeLocale`.
 *
 * Switching language does NOT auto-swap the STT model. A user who
 * picks Italian here keeps whatever STT model they had — the picker
 * lower down in the page is where they trade Kroko/Nemotron for
 * Whisper-turbo if they need non-English transcription.
 */
@Composable
internal fun LanguageSection(
    activeLocale: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_language_blurb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        LANGUAGE_OPTIONS.forEach { option ->
            val active = option.code == activeLocale
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
                        onClick = { onSelect(option.code) },
                    )
                    Text(
                        text = option.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Opt-in toggle: route non-English transcription through the user's
 * configured cloud assistant instead of on-device Whisper-turbo.
 * Off by default — only meaningful when a cloud assistant is
 * configured. Surfaced on the STT settings page; the actual cloud-STT
 * call path reads `SettingsRepository.cloudSttForNonEnglish` when it
 * decides which transcriber to invoke.
 */
@Composable
internal fun CloudSttForNonEnglishSection(
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
                        text = stringResource(R.string.settings_cloud_stt_for_non_english_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_cloud_stt_for_non_english_blurb),
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
                        text = stringResource(R.string.settings_start_on_boot_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_start_on_boot_blurb),
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
internal fun SkillRouterSection(
    enabled: Boolean,
    downloaded: Boolean,
    downloadState: dev.heyari.ari.router.RouterDownloadState,
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
                        text = stringResource(R.string.settings_router_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_router_blurb),
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
            when (downloadState) {
                is dev.heyari.ari.router.RouterDownloadState.Downloading -> {
                    Spacer(Modifier.height(8.dp))
                    val progress = if (downloadState.totalBytes > 0) {
                        downloadState.bytesSoFar.toFloat() / downloadState.totalBytes.toFloat()
                    } else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_router_downloading,
                            formatBytes(downloadState.bytesSoFar),
                            formatBytes(downloadState.totalBytes),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is dev.heyari.ari.router.RouterDownloadState.Failed -> {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_router_download_failed, downloadState.error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> {}
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
                text = stringResource(R.string.settings_default_assistant_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_default_assistant_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onSetAsAssistant) {
                    Text(stringResource(R.string.action_open_settings))
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
            text = stringResource(R.string.settings_llm_blurb),
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
                        text = stringResource(R.string.settings_llm_none_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.settings_llm_none_blurb),
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
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
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
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
                else -> {
                    if (downloadFailed) {
                        Text(
                            text = stringResource(R.string.settings_last_download_failed, (downloadState as LlmDownloadState.Failed).error),
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
                            Text(stringResource(R.string.download_button_with_size, formatBytes(status.model.totalBytes)))
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
