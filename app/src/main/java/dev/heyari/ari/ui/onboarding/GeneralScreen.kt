package dev.heyari.ari.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.pages.IntegrationSection
import dev.heyari.ari.ui.settings.pages.StartOnBootSection

@Composable
fun GeneralScreen(
    settingsViewModel: SettingsViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val state by settingsViewModel.state.collectAsStateWithLifecycle()

    OnboardingScaffold(
        title = stringResource(R.string.onboarding_general_title),
        currentStep = 6,
        onBack = onBack,
        onPrimary = onNext,
    ) {
        Text(
            text = stringResource(R.string.onboarding_general_body),
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(16.dp))

        IntegrationSection(onSetAsAssistant = settingsViewModel::openDefaultAssistantSettings)

        Spacer(Modifier.height(16.dp))

        StartOnBootSection(
            enabled = state.startOnBoot,
            onToggle = settingsViewModel::setStartOnBoot,
        )
    }
}
