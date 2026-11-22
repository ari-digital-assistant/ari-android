package dev.heyari.ari.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.stt.ModelDownloadState
import dev.heyari.ari.stt.SttMode
import dev.heyari.ari.ui.formatBytes
import dev.heyari.ari.ui.settings.ModelStatus
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.pages.CloudSttSection
import dev.heyari.ari.ui.settings.pages.SttModeSection
import dev.heyari.ari.ui.settings.pages.SystemSttSection

@Composable
fun SttScreen(
    settingsViewModel: SettingsViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val state by settingsViewModel.state.collectAsStateWithLifecycle()
    // The model in use when the wizard is run again; otherwise the one
    // recommended for the language. The alternatives are in settings.
    val model = state.models.firstOrNull { it.active && it.downloaded }
        ?: state.models.firstOrNull { it.recommended }

    OnboardingScaffold(
        title = stringResource(R.string.onboarding_stt_title),
        currentStep = 6,
        onBack = onBack,
        onPrimary = {
            // Continuing is what downloads it, as on the assistant screen.
            if (state.sttMode == SttMode.ON_DEVICE && model != null) {
                settingsViewModel.selectAndDownloadModel(model.model)
            }
            onNext()
        },
    ) {
        Text(
            text = buildAnnotatedString {
                append(stringResource(R.string.onboarding_stt_blurb_lead))
                append(" ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(stringResource(R.string.onboarding_stt_blurb_emphasis))
                }
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(16.dp))

        SttModeSection(
            mode = state.sttMode,
            onSelect = settingsViewModel::setSttMode,
        )

        Spacer(Modifier.height(12.dp))

        when (state.sttMode) {
            SttMode.ON_DEVICE -> if (model != null) ModelCard(model, state.download)
            // Not offered here, but a wizard run again after picking it in
            // settings still has to show what is in use.
            SttMode.SYSTEM_SERVICE -> SystemSttSection(
                services = state.sttServices,
                selected = state.sttService,
                onSelect = settingsViewModel::setSttService,
            )
            else -> CloudSttSection(
                mode = state.sttMode,
                endpoint = state.cloudSttEndpoint,
                model = state.cloudSttModel,
                apiKey = state.cloudSttApiKey,
                onEndpointChange = settingsViewModel::setCloudSttEndpoint,
                onModelChange = settingsViewModel::setCloudSttModel,
                onApiKeyChange = settingsViewModel::setCloudSttApiKey,
            )
        }

    }
}

/** What will be downloaded, and how far it has got if it already started. */
@Composable
private fun ModelCard(status: ModelStatus, download: ModelDownloadState) {
    val downloading = (download as? ModelDownloadState.Downloading)
        ?.takeIf { it.modelId == status.model.id }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            Text(
                text = when {
                    status.downloaded -> stringResource(R.string.onboarding_stt_model_downloaded)
                    downloading != null -> stringResource(R.string.onboarding_stt_model_downloading)
                    else -> stringResource(
                        R.string.onboarding_stt_model_on_continue,
                        formatBytes(status.model.totalBytes),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (downloading != null && !status.downloaded) {
                if (downloading.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (downloading.bytesSoFar.toFloat() / downloading.totalBytes).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
