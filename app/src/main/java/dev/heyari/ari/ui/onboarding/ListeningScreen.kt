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
import dev.heyari.ari.listening.ListeningCondition
import dev.heyari.ari.listening.ListeningMode
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.pages.ConditionRow
import dev.heyari.ari.ui.settings.pages.ListeningModeCard

/**
 * Fills the wizard's step-7 hole, right after Wake word: having just chosen
 * how Ari should be summoned, this is the natural moment to say how eagerly it
 * should listen for it.
 *
 * Schedule and Places show as tickable conditions here, same as the settings
 * page, but aren't configurable here — they need a time picker and a map,
 * which don't fit an onboarding screen. Ticking either without configuring
 * anything is fine; [SettingsViewModel.checkPendingListeningSetup] catches
 * that on the way out and the conversation screen nags about it later, exactly
 * like the cloud-assistant reminder.
 */
@Composable
fun ListeningScreen(
    settingsViewModel: SettingsViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val state by settingsViewModel.state.collectAsStateWithLifecycle()

    OnboardingScaffold(
        title = stringResource(R.string.onboarding_listening_title),
        currentStep = 5,
        onBack = onBack,
        onPrimary = {
            settingsViewModel.checkPendingListeningSetup()
            onNext()
        },
    ) {
        Text(
            text = stringResource(R.string.onboarding_listening_body),
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(16.dp))

        ListeningMode.entries.forEach { mode ->
            ListeningModeCard(
                selected = state.listeningMode == mode,
                title = stringResource(mode.labelRes),
                blurb = stringResource(mode.blurbRes),
                onClick = { settingsViewModel.setListeningMode(mode) },
            )
            Spacer(Modifier.height(8.dp))
        }

        if (state.listeningMode == ListeningMode.CUSTOM) {
            Text(
                text = stringResource(R.string.settings_listening_conditions_header),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))

            ListeningCondition.entries.forEach { condition ->
                ConditionRow(
                    title = stringResource(condition.labelRes),
                    blurb = stringResource(condition.blurbRes),
                    checked = condition in state.listeningConditions,
                    onCheckedChange = { settingsViewModel.setListeningCondition(condition, it) },
                    detail = null,
                    onDetailClick = null,
                )
                Spacer(Modifier.height(8.dp))
            }

            Text(
                text = stringResource(R.string.onboarding_listening_custom_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
