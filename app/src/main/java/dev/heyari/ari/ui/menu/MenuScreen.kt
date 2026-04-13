package dev.heyari.ari.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.heyari.ari.BuildConfig
import dev.heyari.ari.R
import dev.heyari.ari.ui.components.AriTopBar
import dev.heyari.ari.ui.settings.components.SettingsCategoryRow

@Composable
fun MenuScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSetupWizard: () -> Unit,
) {
    Scaffold(
        topBar = {
            AriTopBar(
                title = stringResource(R.string.app_name),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsCategoryRow(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.drawer_settings),
                    onClick = onOpenSettings,
                )
                SettingsCategoryRow(
                    icon = Icons.Default.Extension,
                    title = stringResource(R.string.drawer_skills),
                    onClick = onOpenSkills,
                )
                SettingsCategoryRow(
                    icon = Icons.Default.AutoFixHigh,
                    title = stringResource(R.string.drawer_setup_wizard),
                    onClick = onOpenSetupWizard,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            SettingsCategoryRow(
                icon = Icons.Default.Info,
                title = stringResource(R.string.drawer_about),
                onClick = onOpenAbout,
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 16.dp),
            )
        }
    }
}
