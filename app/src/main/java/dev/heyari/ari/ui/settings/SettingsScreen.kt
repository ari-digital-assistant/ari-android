package dev.heyari.ari.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
    onOpenTts: () -> Unit,
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // App Settings
            Text(
                text = stringResource(R.string.settings_section_app),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

            Spacer(Modifier.height(8.dp))

            // Listening & Speech
            Text(
                text = stringResource(R.string.settings_section_listening),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsCategoryRow(
                icon = Icons.Default.Mic,
                title = stringResource(R.string.settings_category_wakeword),
                onClick = onOpenWakeWord,
            )
            SettingsCategoryRow(
                icon = Icons.Default.RecordVoiceOver,
                title = stringResource(R.string.settings_category_tts),
                onClick = onOpenTts,
            )

            Spacer(Modifier.height(8.dp))

            // AI
            Text(
                text = stringResource(R.string.settings_section_ai),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
