package dev.heyari.ari.ui.settings.pages

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.ui.settings.WakeSamplesUiState
import dev.heyari.ari.ui.settings.WakeSamplesViewModel
import dev.heyari.ari.ui.settings.components.SettingsScaffold
import dev.heyari.ari.ui.theme.LocalAriSemanticColors
import dev.heyari.ari.wakeword.SampleBackground
import dev.heyari.ari.wakeword.SampleDistance
import dev.heyari.ari.wakeword.WakeSampleRecorder
import dev.heyari.ari.wakeword.WakeSampleSummary
import java.util.Locale

/**
 * Below this share of audio at speaking level, a recording is too quiet to tell
 * anybody anything. Usable recordings have run 15-53%; a living room with the
 * television on and nobody talking came back at 1%.
 */
private const val QUIET_PERCENT = 10
private const val QUIET_WARNING_AFTER_MS = 20_000L

/**
 * Records wake-phrase audio for the retrain evaluation set.
 *
 * The unit of work is a position, not an utterance: name the set once, pick the
 * conditions, then start a single continuous recording and say the phrase
 * several times. Nobody has to reach the device between takes, which is the
 * only way the across-the-room recordings — the ones that matter most — can be
 * made at all. Splitting into individual takes happens off-device, where the
 * thresholds can be chosen after hearing the room.
 */
@Composable
fun WakeSamplesPage(
    onBack: () -> Unit,
    viewModel: WakeSamplesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportChooserTitle = stringResource(R.string.wake_samples_export_chooser)

    SettingsScaffold(
        title = stringResource(R.string.wake_samples_title),
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
                text = stringResource(R.string.wake_samples_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.setStarted) {
                SetHeader(
                    state = state,
                    onEndSet = viewModel::endSet,
                )
                ConditionsSection(
                    state = state,
                    onRoom = viewModel::setRoom,
                    onDistance = viewModel::setDistance,
                    onBackground = viewModel::setBackground,
                )
                RecorderSection(
                    state = state,
                    onRecord = viewModel::record,
                    onStop = viewModel::stop,
                    onMark = viewModel::mark,
                    onCountdown = viewModel::setCountdown,
                    onDismissProblem = viewModel::acknowledgeProblem,
                )
            } else {
                NewSetSection(
                    state = state,
                    onName = viewModel::setName,
                    onPhrase = viewModel::setPhrase,
                    onStart = viewModel::startSet,
                )
            }

            if (state.segments.isNotEmpty()) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.wake_samples_stored, state.segments.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    state.segments.forEach { segment -> RecordedRow(segment) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                viewModel.shareIntent()?.let { intent ->
                                    context.startActivity(Intent.createChooser(intent, exportChooserTitle))
                                }
                            },
                        ) {
                            Text(stringResource(R.string.wake_samples_export))
                        }
                        TextButton(
                            onClick = viewModel::clear,
                            enabled = !state.isBusy,
                        ) {
                            Text(
                                text = stringResource(R.string.wake_samples_clear),
                                color = LocalAriSemanticColors.current.danger,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewSetSection(
    state: WakeSamplesUiState,
    onName: (String) -> Unit,
    onPhrase: (String) -> Unit,
    onStart: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.setName,
            onValueChange = onName,
            label = { Text(stringResource(R.string.wake_samples_set_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.phrase,
            onValueChange = onPhrase,
            label = { Text(stringResource(R.string.wake_samples_phrase_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onStart,
            enabled = state.canStartSet,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.wake_samples_start_set))
        }
    }
}

@Composable
private fun SetHeader(
    state: WakeSamplesUiState,
    onEndSet: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.setName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = state.phrase,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.wake_samples_segments_recorded, state.segmentsThisSet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEndSet, enabled = !state.isBusy) {
                Text(stringResource(R.string.wake_samples_end_set))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionsSection(
    state: WakeSamplesUiState,
    onRoom: (String) -> Unit,
    onDistance: (SampleDistance) -> Unit,
    onBackground: (SampleBackground) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.room,
            onValueChange = onRoom,
            label = { Text(stringResource(R.string.wake_samples_room_label)) },
            singleLine = true,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.knownRooms.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.knownRooms.forEach { room ->
                    FilterChip(
                        selected = state.room == room,
                        onClick = { onRoom(room) },
                        enabled = !state.isBusy,
                        label = { Text(room, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(28.dp),
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.wake_samples_distance_label),
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleDistance.entries.forEach { option ->
                FilterChip(
                    selected = state.distance == option,
                    onClick = { onDistance(option) },
                    enabled = !state.isBusy,
                    label = { Text(stringResource(option.labelRes), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(28.dp),
                )
            }
        }

        Text(
            text = stringResource(R.string.wake_samples_background_label),
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleBackground.entries.forEach { option ->
                FilterChip(
                    selected = state.background == option,
                    onClick = { onBackground(option) },
                    enabled = !state.isBusy,
                    label = { Text(stringResource(option.labelRes), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(28.dp),
                )
            }
        }
    }
}

@Composable
private fun RecorderSection(
    state: WakeSamplesUiState,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onMark: () -> Unit,
    onCountdown: (Boolean) -> Unit,
    onDismissProblem: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val recorder = state.recorder) {
            is WakeSampleRecorder.State.Idle -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = state.countdown, onCheckedChange = onCountdown)
                    Text(
                        text = stringResource(R.string.wake_samples_countdown_option),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    onClick = onRecord,
                    enabled = state.canRecord,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(stringResource(R.string.wake_samples_record))
                }
                if (!state.canRecord) {
                    Text(
                        text = stringResource(R.string.wake_samples_needs_conditions),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is WakeSampleRecorder.State.CountingDown -> {
                Text(
                    text = stringResource(R.string.wake_samples_countdown, recorder.secondsLeft),
                    style = MaterialTheme.typography.displaySmall,
                )
                LevelMeter(recorder.level)
                TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.wake_samples_cancel))
                }
            }

            is WakeSampleRecorder.State.Recording -> {
                Text(
                    text = formatElapsed(recorder.elapsedMs),
                    style = MaterialTheme.typography.displaySmall,
                )
                LevelMeter(recorder.level)
                Text(
                    text = stringResource(R.string.wake_samples_takes, recorder.utterances),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.wake_samples_loudness, recorder.loudPercent),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Held back for the first stretch: a recording is all quiet
                // until somebody speaks, and warning about that is just noise.
                if (recorder.elapsedMs > QUIET_WARNING_AFTER_MS &&
                    recorder.loudPercent < QUIET_PERCENT
                ) {
                    Text(
                        text = stringResource(R.string.wake_samples_too_quiet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalAriSemanticColors.current.danger,
                    )
                }
                Text(
                    text = stringResource(R.string.wake_samples_recording_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onMark,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(stringResource(R.string.wake_samples_mark, recorder.marks))
                }
                Text(
                    text = stringResource(R.string.wake_samples_mark_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(stringResource(R.string.wake_samples_stop))
                }
            }

            is WakeSampleRecorder.State.Problem -> {
                Text(
                    text = stringResource(recorder.problem.messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalAriSemanticColors.current.danger,
                )
                TextButton(onClick = onDismissProblem, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.wake_samples_dismiss))
                }
            }
        }
    }
}

/**
 * One recorded segment. Says who and where, because "3 files stored" answers
 * neither of the two questions anybody has here — whose voice is captured, and
 * which rooms and distances are still missing.
 */
@Composable
private fun RecordedRow(segment: WakeSampleSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = listOf(segment.setName, segment.room)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    // Falls back to the raw slug rather than an empty gap when a
                    // sidecar names a condition this build does not know.
                    text = listOf(
                        segment.distance?.labelRes?.let { stringResource(it) }
                            ?: segment.distanceSlug,
                        segment.background?.labelRes?.let { stringResource(it) }
                            ?: segment.backgroundSlug,
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (segment.marks.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.wake_samples_marked, segment.marks.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = formatElapsed(segment.durationMs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Deliberately tall: its job is to be readable from the far side of the room,
 * where the speaker is standing and cannot otherwise tell the session is alive.
 */
@Composable
private fun LevelMeter(level: Float) {
    LinearProgressIndicator(
        progress = { level },
        modifier = Modifier.fillMaxWidth().height(16.dp),
    )
}

private fun formatElapsed(ms: Long): String {
    val seconds = ms / 1000L
    return String.format(Locale.getDefault(), "%d:%02d", seconds / 60L, seconds % 60L)
}
