package dev.heyari.ari.ui.settings.pages

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.di.EngineModule
import dev.heyari.ari.llm.LlmDownloadState
import dev.heyari.ari.llm.LlmModelRegistry
import dev.heyari.ari.ui.settings.AssistantUiEntry
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.components.SettingsScaffold
import uniffi.ari_ffi.FfiConfigField
import uniffi.ari_ffi.FfiSelectOption

@Composable
fun AssistantSettingsPage(
    onBack: () -> Unit,
    onOpenSkills: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasCloudAssistant = state.assistantEntries.any { it.privacy == "cloud" }

    SettingsScaffold(
        title = "Assistant",
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Choose how Ari answers general questions when no skill matches.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // "None" option
            AssistantCard(
                name = "None",
                description = "No assistant. Ari only answers via matched skills.",
                privacy = null,
                selected = state.activeAssistantId == null,
                onSelect = { viewModel.selectAssistant(null) },
                configFields = emptyList(),
                onConfigChange = { _, _ -> },
                isBuiltin = false,
                llmModels = emptyList(),
                llmDownloadState = LlmDownloadState.Idle,
                onDownloadModel = {},
                onCancelDownload = {},
                onDeleteModel = {},
                onSelectModel = {},
            )

            // Assistant entries from registry
            for (entry in state.assistantEntries) {
                val isBuiltin = entry.id == EngineModule.BUILTIN_ASSISTANT_ID
                AssistantCard(
                    name = if (isBuiltin) "On-Device Intelligence" else entry.name,
                    description = entry.description,
                    privacy = entry.privacy,
                    selected = state.activeAssistantId == entry.id,
                    onSelect = { viewModel.selectAssistant(entry.id) },
                    configFields = entry.configFields,
                    onConfigChange = { key, value ->
                        viewModel.setAssistantConfig(entry.id, key, value)
                    },
                    isBuiltin = isBuiltin,
                    llmModels = if (isBuiltin) state.llmModels else emptyList(),
                    llmDownloadState = if (isBuiltin) state.llmDownload else LlmDownloadState.Idle,
                    onDownloadModel = { model -> viewModel.downloadLlmModel(model) },
                    onCancelDownload = { viewModel.cancelLlmDownload() },
                    onDeleteModel = { model -> viewModel.deleteLlmModel(model) },
                    onSelectModel = { model -> viewModel.selectLlmModel(model) },
                )
            }

            // Cloud assistant nudge when none are installed
            if (!hasCloudAssistant) {
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Want a cloud assistant?",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Install a cloud assistant skill (ChatGPT, Claude, Gemini, etc.) from the skill browser to add it as an option here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        TextButton(
                            onClick = onOpenSkills,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text("Browse skills")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantCard(
    name: String,
    description: String,
    privacy: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    configFields: List<FfiConfigField>,
    onConfigChange: (String, String) -> Unit,
    isBuiltin: Boolean,
    llmModels: List<dev.heyari.ari.ui.settings.LlmModelStatus>,
    llmDownloadState: LlmDownloadState,
    onDownloadModel: (dev.heyari.ari.llm.LlmModel) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: (dev.heyari.ari.llm.LlmModel) -> Unit,
    onSelectModel: (dev.heyari.ari.llm.LlmModel) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected,
                    onClick = onSelect,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (privacy != null) {
                            Text(
                                text = if (privacy == "cloud") "cloud" else "local",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (privacy == "cloud")
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Show config when selected
            if (selected && isBuiltin && llmModels.isNotEmpty()) {
                // Built-in assistant: show model download UI
                Spacer(modifier = Modifier.height(8.dp))
                BuiltinModelSection(
                    models = llmModels,
                    downloadState = llmDownloadState,
                    onDownload = onDownloadModel,
                    onCancel = onCancelDownload,
                    onDelete = onDeleteModel,
                    onSelect = onSelectModel,
                )
            } else if (selected && configFields.isNotEmpty()) {
                // Cloud/other assistants: show generic config fields
                Spacer(modifier = Modifier.height(8.dp))
                for (field in configFields) {
                    when (field.fieldType) {
                        "text" -> {
                            var localValue by remember(field.key) {
                                mutableStateOf(field.currentValue ?: field.defaultValue ?: "")
                            }
                            OutlinedTextField(
                                value = localValue,
                                onValueChange = { localValue = it },
                                label = { Text(field.label) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { state ->
                                        if (!state.isFocused && localValue.isNotEmpty()) {
                                            onConfigChange(field.key, localValue)
                                        }
                                    },
                                singleLine = true,
                            )
                        }
                        "secret" -> {
                            var localValue by remember(field.key) {
                                mutableStateOf("")
                            }
                            val hasExisting = field.currentValue == "••••••••"
                            OutlinedTextField(
                                value = localValue,
                                onValueChange = { localValue = it },
                                label = { Text(field.label) },
                                placeholder = if (hasExisting) {{ Text("••••••••") }} else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { state ->
                                        if (!state.isFocused && localValue.isNotEmpty()) {
                                            onConfigChange(field.key, localValue)
                                        }
                                    },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                            )
                        }
                        "select" -> {
                            Text(
                                text = field.label,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            for (option in field.options) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 8.dp),
                                ) {
                                    RadioButton(
                                        selected = field.currentValue == option.value,
                                        onClick = { onConfigChange(field.key, option.value) },
                                    )
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            if (selected && privacy == "cloud") {
                Text(
                    text = "Your questions will be sent to a third-party server.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 40.dp, top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun BuiltinModelSection(
    models: List<dev.heyari.ari.ui.settings.LlmModelStatus>,
    downloadState: LlmDownloadState,
    onDownload: (dev.heyari.ari.llm.LlmModel) -> Unit,
    onCancel: () -> Unit,
    onDelete: (dev.heyari.ari.llm.LlmModel) -> Unit,
    onSelect: (dev.heyari.ari.llm.LlmModel) -> Unit,
) {
    Text(
        text = "Choose a model to download. Smaller models are faster but less capable.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 40.dp),
    )
    Spacer(modifier = Modifier.height(4.dp))

    for (status in models) {
        val model = status.model
        val isDownloading = downloadState is LlmDownloadState.Downloading
                && downloadState.modelId == model.id
        val failed = downloadState is LlmDownloadState.Failed
                && downloadState.modelId == model.id

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp, top = 4.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (status.downloaded) {
                        RadioButton(
                            selected = status.active,
                            onClick = { onSelect(model) },
                        )
                    } else {
                        Spacer(modifier = Modifier.width(48.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = model.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (isDownloading) {
                    val dl = downloadState as LlmDownloadState.Downloading
                    val progress = if (dl.totalBytes > 0) {
                        dl.bytesSoFar.toFloat() / dl.totalBytes.toFloat()
                    } else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${formatBytes(dl.bytesSoFar)} / ${formatBytes(dl.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        TextButton(onClick = onCancel) {
                            Text("Cancel")
                        }
                    }
                } else if (status.downloaded) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = { onDelete(model) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Delete")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (failed) {
                            Text(
                                text = (downloadState as LlmDownloadState.Failed).error,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                            )
                        }
                        Button(onClick = { onDownload(model) }) {
                            Text("Download ${formatBytes(model.totalBytes)}")
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}
