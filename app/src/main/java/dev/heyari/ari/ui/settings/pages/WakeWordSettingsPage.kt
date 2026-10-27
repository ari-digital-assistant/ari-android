package dev.heyari.ari.ui.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.components.SettingsScaffold

@Composable
fun WakeWordSettingsPage(
    onBack: () -> Unit,
    onTune: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_category_wakeword),
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
            WakeWordSection(
                wakeWords = state.wakeWords,
                onSelect = viewModel::selectWakeWord,
            )
            WakeWordSensitivitySection(
                current = state.wakeWordSensitivity,
                onSelect = viewModel::selectWakeWordSensitivity,
            )
            Button(onClick = onTune, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_wakeword_tune))
            }
            RecordingToggleRow(
                title = stringResource(R.string.settings_wake_speech_check_title),
                blurb = stringResource(R.string.settings_wake_speech_check_blurb),
                checked = state.wakeSpeechCheck,
                onCheckedChange = viewModel::setWakeSpeechCheck,
            )
            RecordingToggleRow(
                title = stringResource(R.string.settings_quiet_wake_title),
                blurb = stringResource(R.string.settings_quiet_wake_blurb),
                checked = state.quietWake,
                onCheckedChange = viewModel::setQuietWake,
            )
            ShadowWakeWordSection(
                options = state.shadowWakeWords.map { it.id to it.displayName },
                selected = state.shadowWakeWordId,
                onSelect = viewModel::setShadowWakeWord,
            )
            if (state.shadowWakeWordId != null) {
                RecordingToggleRow(
                    title = stringResource(R.string.settings_shadow_recordings_title),
                    blurb = stringResource(R.string.settings_shadow_recordings_blurb),
                    checked = state.shadowRecordings,
                    onCheckedChange = viewModel::setShadowRecordings,
                )
            }
            // The false-trigger capture toggle lives on the Debug page now,
            // beside the other recording switches.
        }
    }
}

/** Off, or one of [options] (id to name) running silently beside the live wake word. */
@Composable
private fun ShadowWakeWordSection(
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_shadow_wake_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.settings_shadow_wake_blurb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        (listOf(null to stringResource(R.string.settings_shadow_wake_off)) + options).forEach { (id, name) ->
            val active = id == selected
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = active, onClick = { onSelect(id) }, role = Role.RadioButton),
                colors = CardDefaults.cardColors(
                    containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = active, onClick = null, modifier = Modifier.minimumInteractiveComponentSize())
                    Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
