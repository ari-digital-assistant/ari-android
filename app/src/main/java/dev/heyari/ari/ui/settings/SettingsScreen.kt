package dev.heyari.ari.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R
import dev.heyari.ari.ui.settings.components.SettingsCategoryRow
import dev.heyari.ari.ui.settings.components.SettingsScaffold

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenGeneral: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenWakeWord: () -> Unit,
    onOpenStt: () -> Unit,
    onOpenLlm: () -> Unit,
) {
    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCategoryRow(
                icon = Icons.Default.Settings,
                title = stringResource(R.string.settings_category_general),
                onClick = onOpenGeneral,
            )
            SettingsCategoryRow(
                icon = Icons.Default.Shield,
                title = stringResource(R.string.settings_category_permissions),
                onClick = onOpenPermissions,
            )
            SettingsCategoryRow(
                icon = Icons.Default.Mic,
                title = stringResource(R.string.settings_category_wakeword),
                onClick = onOpenWakeWord,
            )
            SettingsCategoryRow(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = stringResource(R.string.settings_category_stt),
                onClick = onOpenStt,
            )
            SettingsCategoryRow(
                icon = Icons.Default.AutoAwesome,
                title = stringResource(R.string.settings_category_assistant),
                onClick = onOpenLlm,
            )
        }
    }
}
