package dev.heyari.ari.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import dev.heyari.ari.stt.SttMode
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.pages.CloudSttSection
import dev.heyari.ari.ui.settings.pages.ModelsSection
import dev.heyari.ari.ui.settings.pages.SttModeSection

@Composable
fun SttScreen(
    settingsViewModel: SettingsViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val state by settingsViewModel.state.collectAsStateWithLifecycle()

    OnboardingScaffold(
        title = stringResource(R.string.onboarding_stt_title),
        currentStep = 5,
        onBack = onBack,
        onPrimary = onNext,
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
            SttMode.ON_DEVICE -> ModelsSection(
                models = state.models,
                downloadState = state.download,
                onDownload = settingsViewModel::downloadModel,
                onCancel = settingsViewModel::cancelDownload,
                onDelete = settingsViewModel::deleteModel,
                onSelect = settingsViewModel::selectModel,
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

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_stt_continue_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
