package dev.heyari.ari.ui.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.listening.ListeningCondition
import dev.heyari.ari.listening.ListeningMode
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.components.SettingsScaffold

@Composable
fun ListeningSettingsPage(
    onBack: () -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenPlaces: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_category_listening),
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_listening_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ListeningMode.entries.forEach { mode ->
                ListeningModeCard(
                    selected = state.listeningMode == mode,
                    title = stringResource(mode.labelRes),
                    blurb = stringResource(mode.blurbRes),
                    onClick = { viewModel.setListeningMode(mode) },
                )
            }

            if (state.listeningMode == ListeningMode.CUSTOM) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_listening_conditions_header),
                    style = MaterialTheme.typography.titleSmall,
                )

                if (state.listeningConditions.isEmpty()) {
                    NoticeCard(text = stringResource(R.string.settings_listening_no_conditions_notice))
                }

                ListeningCondition.entries.forEach { condition ->
                    val ticked = condition in state.listeningConditions
                    ConditionRow(
                        title = stringResource(condition.labelRes),
                        blurb = stringResource(condition.blurbRes),
                        checked = ticked,
                        onCheckedChange = { viewModel.setListeningCondition(condition, it) },
                        detail = when (condition) {
                            ListeningCondition.SCHEDULE -> pluralStringResource(
                                R.plurals.settings_listening_schedule_count,
                                state.listeningSchedules.size,
                                state.listeningSchedules.size,
                            )

                            ListeningCondition.PLACE -> pluralStringResource(
                                R.plurals.settings_listening_place_count,
                                state.listeningPlaces.size,
                                state.listeningPlaces.size,
                            )

                            else -> null
                        },
                        onDetailClick = when (condition) {
                            ListeningCondition.SCHEDULE -> onOpenSchedules
                            ListeningCondition.PLACE -> onOpenPlaces
                            else -> null
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ListeningModeCard(
    selected: Boolean,
    title: String,
    blurb: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A tickable condition. Schedule and Places also carry a count that navigates
 * onward — the tick and the configuration are separate acts, so ticking
 * "on a schedule" with no schedules yet is allowed and simply says "0 windows".
 */
@Composable
internal fun ConditionRow(
    title: String,
    blurb: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    detail: String?,
    onDetailClick: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = onCheckedChange)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (detail != null && onDetailClick != null) {
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    onClick = onDetailClick,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun NoticeCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}
