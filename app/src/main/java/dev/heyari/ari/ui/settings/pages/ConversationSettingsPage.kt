package dev.heyari.ari.ui.settings.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.heyari.ari.R
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.components.SettingsScaffold

@Composable
fun ConversationSettingsPage(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    SettingsScaffold(
        title = stringResource(R.string.settings_category_conversation),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_conversation_memory_title))
                    Text(
                        text = stringResource(R.string.settings_conversation_memory_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.conversationMemoryEnabled,
                    onCheckedChange = viewModel::setConversationMemoryEnabled,
                )
            }
            AnimatedVisibility(visible = !state.conversationMemoryEnabled) {
                Text(
                    text = stringResource(R.string.settings_conversation_memory_disabled_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_conversation_barge_in_title))
                    Text(
                        text = stringResource(R.string.settings_conversation_barge_in_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.bargeInEnabled,
                    onCheckedChange = viewModel::setBargeInEnabled,
                )
            }
            HorizontalDivider()
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_remembered_facts_title))
                Text(
                    text = stringResource(R.string.settings_remembered_facts_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (state.rememberedFacts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_remembered_facts_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.rememberedFacts.forEach { fact ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = fact, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.forgetFact(fact) }) {
                                Text(stringResource(R.string.settings_remembered_facts_delete))
                            }
                        }
                    }
                    OutlinedButton(onClick = { viewModel.forgetAllFacts() }) {
                        Text(stringResource(R.string.settings_remembered_facts_forget_all))
                    }
                }
            }
        }
    }
}
