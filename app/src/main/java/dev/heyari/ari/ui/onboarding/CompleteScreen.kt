package dev.heyari.ari.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R

@Composable
fun CompleteScreen(
    onboardingViewModel: OnboardingViewModel,
    onDone: () -> Unit,
    onBrowseCloudSkills: () -> Unit,
) {
    val wizardState by onboardingViewModel.state.collectAsStateWithLifecycle()

    val title = if (wizardState.isRevisit) {
        stringResource(R.string.onboarding_complete_title_revisit)
    } else {
        stringResource(R.string.onboarding_complete_title)
    }

    OnboardingScaffold(
        title = title,
        currentStep = 7,
        primaryLabel = stringResource(R.string.onboarding_done),
        onPrimary = {
            onboardingViewModel.completeOnboarding()
            onDone()
        },
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onboarding_complete_try),
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_complete_example),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_complete_or_type),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Cloud assistant nudge
        if (wizardState.assistantChoice == AssistantChoice.CLOUD) {
            Spacer(Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_complete_cloud_nudge),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                Button(
                    onClick = onBrowseCloudSkills,
                    modifier = Modifier.padding(start = 16.dp, bottom = 16.dp),
                ) {
                    Text(stringResource(R.string.onboarding_complete_browse_cloud))
                }
            }
        }

        // Mic denied note
        if (wizardState.micDenied) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.onboarding_complete_mic_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
