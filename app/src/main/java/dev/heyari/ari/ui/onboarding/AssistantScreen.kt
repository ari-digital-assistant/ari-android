package dev.heyari.ari.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.llm.LlmDownloadState
import dev.heyari.ari.llm.LlmModel
import dev.heyari.ari.llm.LlmModelRegistry
import dev.heyari.ari.ui.settings.SettingsViewModel
import java.util.Locale

@Composable
fun AssistantScreen(
    settingsViewModel: SettingsViewModel,
    onboardingViewModel: OnboardingViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val state by settingsViewModel.state.collectAsStateWithLifecycle()
    val wizardState by onboardingViewModel.state.collectAsStateWithLifecycle()

    // Auto-select Small when the user first picks On-device.
    LaunchedEffect(wizardState.assistantChoice) {
        if (wizardState.assistantChoice == AssistantChoice.ON_DEVICE && wizardState.selectedLlmModelId == null) {
            onboardingViewModel.setSelectedLlmModelId(LlmModelRegistry.SMALL.id)
        }
    }

    OnboardingScaffold(
        title = stringResource(R.string.onboarding_assistant_title),
        currentStep = 5,
        onBack = onBack,
        onPrimary = {
            // If on-device is selected and a model is chosen, start the download
            // (if not already downloaded/downloading) before navigating.
            if (wizardState.assistantChoice == AssistantChoice.ON_DEVICE) {
                val modelId = wizardState.selectedLlmModelId
                if (modelId != null) {
                    val model = LlmModelRegistry.byId(modelId)
                    if (model != null) {
                        settingsViewModel.selectLlmModel(model)
                        settingsViewModel.downloadLlmModel(model)
                    }
                }
            }
            onNext()
        },
    ) {
        Text(
            text = stringResource(R.string.onboarding_assistant_body),
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(16.dp))

        // None
        AssistantChoiceCard(
            label = stringResource(R.string.onboarding_assistant_none),
            blurb = stringResource(R.string.onboarding_assistant_none_blurb),
            pros = listOf(
                stringResource(R.string.onboarding_assistant_none_pro_1),
                stringResource(R.string.onboarding_assistant_none_pro_2),
                stringResource(R.string.onboarding_assistant_none_pro_3),
            ),
            cons = listOf(
                stringResource(R.string.onboarding_assistant_none_con_1),
            ),
            selected = wizardState.assistantChoice == AssistantChoice.NONE,
            onClick = {
                onboardingViewModel.setAssistantChoice(AssistantChoice.NONE)
                settingsViewModel.selectAssistant(null)
            },
        )

        Spacer(Modifier.height(8.dp))

        // On-device
        AssistantChoiceCard(
            label = stringResource(R.string.onboarding_assistant_ondevice),
            blurb = stringResource(R.string.onboarding_assistant_ondevice_blurb),
            pros = listOf(
                stringResource(R.string.onboarding_assistant_ondevice_pro_1),
                stringResource(R.string.onboarding_assistant_ondevice_pro_2),
            ),
            cons = listOf(
                stringResource(R.string.onboarding_assistant_ondevice_con_1),
                stringResource(R.string.onboarding_assistant_ondevice_con_2),
            ),
            selected = wizardState.assistantChoice == AssistantChoice.ON_DEVICE,
            onClick = {
                onboardingViewModel.setAssistantChoice(AssistantChoice.ON_DEVICE)
                settingsViewModel.selectAssistant("builtin")
            },
        )

        AnimatedVisibility(visible = wizardState.assistantChoice == AssistantChoice.ON_DEVICE) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = "Choose a model size. You can change this later in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                LlmModelRegistry.all.forEach { model ->
                    val selected = wizardState.selectedLlmModelId == model.id
                    val isDownloading = state.llmDownload is LlmDownloadState.Downloading &&
                        (state.llmDownload as LlmDownloadState.Downloading).modelId == model.id
                    val downloaded = state.llmModels.firstOrNull { it.model.id == model.id }?.downloaded == true

                    LlmTierRow(
                        model = model,
                        selected = selected,
                        downloaded = downloaded,
                        isDownloading = isDownloading,
                        downloadState = if (isDownloading) state.llmDownload as LlmDownloadState.Downloading else null,
                        onSelect = { onboardingViewModel.setSelectedLlmModelId(model.id) },
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Cloud
        AssistantChoiceCard(
            label = stringResource(R.string.onboarding_assistant_cloud),
            blurb = stringResource(R.string.onboarding_assistant_cloud_blurb),
            pros = listOf(
                stringResource(R.string.onboarding_assistant_cloud_pro_1),
                stringResource(R.string.onboarding_assistant_cloud_pro_2),
            ),
            cons = listOf(
                stringResource(R.string.onboarding_assistant_cloud_con_1),
                stringResource(R.string.onboarding_assistant_cloud_con_2),
                stringResource(R.string.onboarding_assistant_cloud_con_3),
            ),
            selected = wizardState.assistantChoice == AssistantChoice.CLOUD,
            onClick = {
                onboardingViewModel.setAssistantChoice(AssistantChoice.CLOUD)
            },
        )

        AnimatedVisibility(visible = wizardState.assistantChoice == AssistantChoice.CLOUD) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = stringResource(R.string.onboarding_assistant_cloud_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_assistant_cloud_later),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantChoiceCard(
    label: String,
    blurb: String,
    pros: List<String>,
    cons: List<String>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pros.isNotEmpty() || cons.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (pro in pros) {
                            ProConRow(text = pro, isPro = true)
                        }
                        for (con in cons) {
                            ProConRow(text = con, isPro = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProConRow(text: String, isPro: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isPro) Icons.Default.AddCircle else Icons.Default.RemoveCircle,
            contentDescription = null,
            tint = if (isPro) Color(0xFF2E7D32) else Color(0xFFC62828),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LlmTierRow(
    model: LlmModel,
    selected: Boolean,
    downloaded: Boolean,
    isDownloading: Boolean,
    downloadState: LlmDownloadState.Downloading?,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (downloaded) {
                Text(
                    text = "Downloaded",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 48.dp),
                )
            }
            if (isDownloading && downloadState != null && downloadState.totalBytes > 0) {
                val progress = downloadState.bytesSoFar.toFloat() / downloadState.totalBytes.toFloat()
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Text(
                    text = "${formatBytes(downloadState.bytesSoFar)} / ${formatBytes(downloadState.totalBytes)} (${String.format(Locale.US, "%.0f", progress * 100)}%)",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 48.dp, top = 4.dp),
                )
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
