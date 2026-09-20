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
import dev.heyari.ari.wakeword.LadderFit
import dev.heyari.ari.wakeword.SensitivityTuner
import dev.heyari.ari.wakeword.Take

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
                takes = viewModel.takes,
                onStart = viewModel::start,
                onDismissProblem = viewModel::dismissProblem,
            )

            HorizontalDivider()

            RecordingToggleRow(
                title = stringResource(R.string.sensitivity_tuning_normal_use_title),
                blurb = stringResource(R.string.sensitivity_tuning_normal_use_blurb),
                checked = state.tuneDuringNormalUse,
                onCheckedChange = viewModel::setTuneDuringNormalUse,
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
    takes: List<Take>,
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
                Text(
                    text = stringResource(R.string.sensitivity_tuning_quiet_advice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onStart,
                    enabled = state.canStart,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(
                        stringResource(
                            if (state.sessions.isEmpty()) {
                                R.string.sensitivity_tuning_start
                            } else {
                                R.string.sensitivity_tuning_add_session
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
                // Shown a beat before the mic opens, so there is time to read a
                // decoy line rather than discover it while being recorded.
                prompt = stringResource(tuner.prompt.promptRes),
            )

            is SensitivityTuner.State.Listening -> Prompt(
                headline = stringResource(R.string.sensitivity_tuning_say_it),
                caption = stringResource(
                    R.string.sensitivity_tuning_attempt, tuner.attempt, tuner.total,
                ),
                level = tuner.level,
                prompt = stringResource(tuner.prompt.promptRes),
            )

            is SensitivityTuner.State.Scoring -> Prompt(
                headline = stringResource(R.string.sensitivity_tuning_scoring),
                caption = stringResource(
                    R.string.sensitivity_tuning_attempt, tuner.done, tuner.total,
                ),
                level = if (tuner.total == 0) 0f else tuner.done.toFloat() / tuner.total,
            )

            is SensitivityTuner.State.Problem -> Unit
        }

        if (state.fit != null && !state.isBusy) {
            Result(state.fit)
        }
    }
}

@Composable
private fun Prompt(headline: String, caption: String, level: Float, prompt: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = headline, style = MaterialTheme.typography.headlineSmall)
        if (prompt != null) {
            Text(
                text = "\u201c$prompt\u201d",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
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
private fun Result(fit: LadderFit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (fit.overlapping) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.sensitivity_tuning_result),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(
                    R.string.sensitivity_tuning_result_detail,
                    fit.phrasesHeard,
                    fit.phrasesTotal,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (fit.overlapping) {
                // The one result the screen must not dress up. When ordinary
                // speech scores as high as the wake phrase there is no cutoff
                // that separates them, and a reassuring tick over that is how
                // somebody ends up living with a wake word nobody measured.
                Text(
                    text = stringResource(R.string.sensitivity_tuning_overlapping),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalAriSemanticColors.current.danger,
                )
            }
        }
    }
}
