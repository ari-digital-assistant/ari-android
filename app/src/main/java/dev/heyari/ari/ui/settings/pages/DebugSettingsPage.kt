package dev.heyari.ari.ui.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.heyari.ari.BuildConfig
import dev.heyari.ari.ui.theme.LocalAriSemanticColors
import dev.heyari.ari.R
import dev.heyari.ari.ui.settings.components.SettingsScaffold

/**
 * Tester tools: making a recording on purpose, breaking the app on purpose,
 * and taking back a report already sent.
 *
 * The passive capture toggles used to live here too. They moved to Settings >
 * Developer when sharing was added — a switch that can put audio on somebody
 * else's computer belongs next to the one that decides whether it may, not
 * next to a button that crashes the app.
 */
@Composable
fun DebugSettingsPage(
    onBack: () -> Unit,
    onOpenMyReports: () -> Unit,
    onOpenWakeSamples: () -> Unit,
) {
    SettingsScaffold(
        title = stringResource(R.string.settings_category_debug),
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
            // Deliberate recording rather than passive capture, so it gets a
            // page of its own: a session has a person in it who needs telling
            // when to speak and where to stand.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenWakeSamples)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_wake_samples_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_wake_samples_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // A deliberate crash, so the crash prompt and the trace it carries
            // can be exercised on purpose rather than waited for. Testing
            // builds only — the flag that gates the reporter gates this too,
            // because a button that kills the app has no business in a release.
            if (BuildConfig.ARI_TESTING) {
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            throw IllegalStateException(
                                "Deliberate crash from Settings > Debug, to test the reporter"
                            )
                        }
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_crash_now_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = LocalAriSemanticColors.current.danger,
                    )
                    Text(
                        text = stringResource(R.string.settings_crash_now_blurb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Last, because it is where a tester goes to undo something rather
            // than to configure anything.
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenMyReports)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_my_reports_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_my_reports_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
