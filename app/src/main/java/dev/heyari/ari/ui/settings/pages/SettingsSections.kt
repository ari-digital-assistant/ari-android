package dev.heyari.ari.ui.settings.pages

import android.text.format.Formatter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R
import dev.heyari.ari.audio.ClipStats
import dev.heyari.ari.llm.LlmDownloadState
import dev.heyari.ari.llm.LlmModel
import dev.heyari.ari.stt.ModelDownloadState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.heyari.ari.stt.SttMode
import dev.heyari.ari.stt.SttModel
import dev.heyari.ari.ui.components.MicDisclosureDialog
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
    onOpenOverlaySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    // Location and full-screen alerts are no longer core, asked-up-front
    // permissions: each is requested at skill-install time by whichever
    // skill declares the matching capability (`location` → weather etc;
    // `critical_alert` → timer etc). Onboarding hides both rows
    // (showLocation / showFsn = false); the Settings → Permissions page
    // keeps them so a user who declined at install can manage them later.
    showLocation: Boolean = true,
    onRequestLocation: () -> Unit = {},
    showFsn: Boolean = true,
    onOpenFsnSettings: () -> Unit = {},
) {
    // Both callers of this section — onboarding and Settings — can be the
    // first place a user is asked for the microphone, so the disclosure lives
    // here rather than in either screen.
    var showMicDisclosure by rememberSaveable { mutableStateOf(false) }

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
            onAction = {
                if (permissions.recordAudio) onOpenAppSettings() else showMicDisclosure = true
            },
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

        if (showLocation) {
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
        }

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

        if (showFsn) {
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

    if (showMicDisclosure) {
        MicDisclosureDialog(
            onDismiss = { showMicDisclosure = false },
            onContinue = {
                showMicDisclosure = false
                onRequestRecordAudio()
            },
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
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = option.active,
                        onClick = { onSelect(option.model) },
                        role = Role.RadioButton,
                    ),
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
                        onClick = null,
                        modifier = Modifier.minimumInteractiveComponentSize(),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = active,
                        onClick = { onSelect(option) },
                        role = Role.RadioButton,
                    ),
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
                        onClick = null,
                        modifier = Modifier.minimumInteractiveComponentSize(),
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

/**
 * One debug audio-capture toggle: switch, blurb, live clip count, and — once
 * there is something to act on — export and delete. Shared by the wake-word
 * false-trigger capture and the spoken-command capture; only the [title] and
 * [blurb] differ.
 */
@Composable
internal fun AudioCaptureSection(
    title: String,
    blurb: String,
    enabled: Boolean,
    stats: ClipStats,
    onToggle: (Boolean) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Text(
            text = blurb,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (stats.count == 0) {
                stringResource(R.string.settings_capture_empty)
            } else {
                pluralStringResource(
                    R.plurals.settings_capture_stats,
                    stats.count,
                    stats.count,
                    Formatter.formatShortFileSize(context, stats.totalBytes),
                )
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (stats.count > 0) {
            OutlinedButton(onClick = onExport) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_capture_export))
            }
            OutlinedButton(onClick = onClear) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_capture_delete))
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
        modifier = Modifier
            .fillMaxWidth()
            // Only a model that's actually on disk can be picked. Undownloaded
            // ones keep their own Download button and nothing else.
            .then(
                if (status.downloaded) {
                    Modifier.selectable(
                        selected = status.active,
                        onClick = onSelect,
                        role = Role.RadioButton,
                    )
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (status.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status.downloaded) {
                    RadioButton(
                        selected = status.active,
                        onClick = null,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    )
                } else {
                    Spacer(Modifier.width(48.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(status.model.displayNameRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(status.model.descriptionRes),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = active,
                        onClick = { onSelect(option.code) },
                        role = Role.RadioButton,
                    ),
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
                        onClick = null,
                        modifier = Modifier.minimumInteractiveComponentSize(),
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
 * The whole STT choice: on device, or cloud. Shared by the settings page and
 * the onboarding wizard so both offer the same two options in the same words.
 *
 * Which local model backs on-device is not shown, because it is not a choice —
 * the user's language decides it (see [SttModelRegistry.onDeviceFor]).
 */
@Composable
internal fun SttModeSection(
    mode: SttMode,
    onSelect: (SttMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SttModeCard(
            selected = mode == SttMode.ON_DEVICE,
            title = stringResource(R.string.settings_stt_mode_on_device_title),
            blurb = stringResource(R.string.settings_stt_mode_on_device_blurb),
            onClick = { onSelect(SttMode.ON_DEVICE) },
        )
        SttModeCard(
            selected = mode == SttMode.OPENAI,
            title = stringResource(R.string.settings_stt_mode_openai_title),
            blurb = stringResource(R.string.settings_stt_mode_openai_blurb),
            onClick = { onSelect(SttMode.OPENAI) },
        )
        SttModeCard(
            selected = mode == SttMode.SELF_HOSTED,
            title = stringResource(R.string.settings_stt_mode_self_hosted_title),
            blurb = stringResource(R.string.settings_stt_mode_self_hosted_blurb),
            onClick = { onSelect(SttMode.SELF_HOSTED) },
        )
    }
}

@Composable
private fun SttModeCard(
    selected: Boolean,
    title: String,
    blurb: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                modifier = Modifier.minimumInteractiveComponentSize(),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Config for whichever cloud mode is selected — and only the fields that mode
 * actually needs.
 *
 * [SttMode.OPENAI] asks for a key and nothing else: we know the endpoint and we
 * pick the model, which is the entire value of offering a preset. Making
 * everyone paste a URL to use the obvious option was the wrong default.
 *
 * [SttMode.SELF_HOSTED] is the mirror image — we know neither the URL nor what
 * that server calls its model, and its key is genuinely optional because most
 * self-hosted Whisper builds authenticate nothing.
 */
@Composable
internal fun CloudSttSection(
    mode: SttMode,
    endpoint: String,
    model: String,
    apiKey: String,
    onEndpointChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
) {
    val selfHosted = mode == SttMode.SELF_HOSTED
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (selfHosted) R.string.settings_stt_self_hosted_blurb
                    else R.string.settings_stt_openai_blurb,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selfHosted) {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = onEndpointChange,
                    label = { Text(stringResource(R.string.settings_stt_cloud_endpoint_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = onModelChange,
                    label = { Text(stringResource(R.string.settings_stt_cloud_model_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // The key is held locally and persisted once, on dispose —
            // storing it means a Keystore round-trip, which is far too
            // expensive to do per keystroke. Same deal as the secret fields
            // in SkillSettingsPanel.
            var typed by remember { mutableStateOf(false) }
            var localKey by remember { mutableStateOf(apiKey) }
            val flush = rememberUpdatedState(onApiKeyChange)
            // The stored key is read off the main thread, so it can land
            // after this field first composes. Take it until the user starts
            // typing; after that the field owns the value.
            LaunchedEffect(apiKey) {
                if (!typed) localKey = apiKey
            }
            OutlinedTextField(
                value = localKey,
                onValueChange = { typed = true; localKey = it },
                label = { Text(stringResource(R.string.settings_stt_cloud_api_key_label)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (selfHosted) R.string.settings_stt_cloud_api_key_hint_optional
                            else R.string.settings_stt_cloud_api_key_hint_required,
                        ),
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            DisposableEffect(Unit) {
                onDispose { if (typed) flush.value(localKey) }
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
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, onValueChange = onToggle, role = Role.Switch),
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
                    onCheckedChange = null,
                    modifier = Modifier.minimumInteractiveComponentSize(),
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
        modifier = Modifier
            .fillMaxWidth()
            // Only a model that's actually on disk can be picked. Undownloaded
            // ones keep their own Download button and nothing else.
            .then(
                if (status.downloaded) {
                    Modifier.selectable(
                        selected = status.active,
                        onClick = onSelect,
                        role = Role.RadioButton,
                    )
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (status.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status.downloaded) {
                    RadioButton(
                        selected = status.active,
                        onClick = null,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    )
                } else {
                    Spacer(Modifier.width(48.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(status.model.displayNameRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(status.model.descriptionRes),
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
