package dev.heyari.ari.ui.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.heyari.ari.R
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.components.SettingsScaffold

@Composable
fun GeneralSettingsPage(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    SettingsScaffold(
        title = stringResource(R.string.settings_category_general),
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IntegrationSection(onSetAsAssistant = viewModel::openDefaultAssistantSettings)
            StartOnBootSection(
                enabled = state.startOnBoot,
                onToggle = viewModel::setStartOnBoot,
            )
            SkillRouterSection(
                enabled = state.routerEnabled,
                downloaded = state.routerDownloaded,
                onToggle = viewModel::setRouterEnabled,
            )
        }
    }
}
