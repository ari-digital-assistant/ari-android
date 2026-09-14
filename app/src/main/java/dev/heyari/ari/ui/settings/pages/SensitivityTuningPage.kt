package dev.heyari.ari.ui.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.ui.settings.SensitivityTuningUiState
import dev.heyari.ari.ui.settings.SensitivityTuningViewModel
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.components.SettingsScaffold
import dev.heyari.ari.ui.theme.LocalAriSemanticColors
import dev.heyari.ari.wakeword.SensitivityTuner
import dev.heyari.ari.wakeword.TunedSpeaker

/**
 * Two ways to settle the sensitivity: measure it, or pick it.
 *
 * Measuring exists because the three words on the picker mean nothing to
 * anybody. The setting shipped for months heard one member of the test
 * household six times in fifteen beside the phone and twice in fifteen across a
 * quiet room, and there was no way to discover that short of living with it.
 */
@Composable
fun SensitivityTuningPage(
    onBack: () -> Unit,
    viewModel: SensitivityTuningViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.sensitivity_tuning_title),
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = stringResource(R.string.sensitivity_tuning_blurb),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TunerSection(
                state = state,
                onName = viewModel::setName,
                onStart = viewModel::start,
                onDismissProblem = viewModel::dismissProblem,
            )

            HorizontalDivider()

            Text(
                text = stringResource(R.string.sensitivity_tuning_manual_heading),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            WakeWordSensitivitySection(
                current = settings.wakeWordSensitivity,
                onSelect = settingsViewModel::selectWakeWordSensitivity,
            )
        }
    }
}

@Composable
private fun TunerSection(
    state: SensitivityTuningUiState,
    onName: (String) -> Unit,
    onStart: () -> Unit,
    onDismissProblem: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.problem?.let { problem ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(problem.messageRes))
                    TextButton(onClick = onDismissProblem) {
                        Text(stringResource(R.string.wake_samples_dismiss))
                    }
                }
            }
        }

        when (val tuner = state.tuner) {
            is SensitivityTuner.State.Idle -> {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.sensitivity_tuning_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onStart,
                    enabled = state.canStart,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(
                        stringResource(
                            if (state.speakers.isEmpty()) {
                                R.string.sensitivity_tuning_start
                            } else {
                                R.string.sensitivity_tuning_add_person
                            },
                        ),
                    )
                }
            }

            is SensitivityTuner.State.WarmingUp -> Prompt(
                headline = stringResource(R.string.sensitivity_tuning_warmup),
                caption = stringResource(
                    R.string.sensitivity_tuning_warmup_caption, tuner.secondsLeft,
                ),
                level = tuner.level,
            )

            is SensitivityTuner.State.GetReady -> Prompt(
                headline = stringResource(R.string.sensitivity_tuning_get_ready),
                caption = stringResource(
                    R.string.sensitivity_tuning_attempt, tuner.attempt, tuner.total,
                ),
                level = 0f,
            )

            is SensitivityTuner.State.Listening -> Prompt(
                headline = stringResource(R.string.sensitivity_tuning_say_it),
                caption = stringResource(
                    R.string.sensitivity_tuning_attempt, tuner.attempt, tuner.total,
                ),
                level = tuner.level,
            )

            is SensitivityTuner.State.Scoring -> Prompt(
                headline = stringResource(R.string.sensitivity_tuning_scoring),
                caption = "",
                level = 0f,
            )

            is SensitivityTuner.State.Problem -> Unit
        }

        if (state.speakers.isNotEmpty() && !state.isBusy) {
            Result(state)
        }
    }
}

@Composable
private fun Prompt(headline: String, caption: String, level: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = headline, style = MaterialTheme.typography.headlineSmall)
        if (caption.isNotBlank()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { level },
            modifier = Modifier.fillMaxWidth().height(8.dp),
        )
    }
}

@Composable
private fun Result(state: SensitivityTuningUiState) {
    val chosen = state.chosen ?: return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (state.shortOfTheBar) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(
                    R.string.sensitivity_tuning_result,
                    stringResource(chosen.displayNameRes),
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = state.constrainedBy?.let {
                    stringResource(R.string.sensitivity_tuning_shared_for, it.name)
                } ?: stringResource(R.string.sensitivity_tuning_shared),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.speakers.forEach { speaker -> SpeakerRow(speaker, state) }
            if (state.shortOfTheBar) {
                Text(
                    text = stringResource(R.string.sensitivity_tuning_short_of_bar),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalAriSemanticColors.current.danger,
                )
            }
        }
    }
}

@Composable
private fun SpeakerRow(speaker: TunedSpeaker, state: SensitivityTuningUiState) {
    val heard = speaker.heardAt[state.chosen] ?: 0
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = speaker.name, modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.sensitivity_tuning_heard, heard, speaker.attempts),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
