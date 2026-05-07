package dev.heyari.ari.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.pages.SkillRouterSection

/**
 * Onboarding step 6 — Smart skill routing. The FunctionGemma router
 * (~253 MB) catches paraphrases the keyword scorer misses ("launch my
 * music player" → open-app skill). Default-on so new users get
 * smarter routing for free; the toggle lets them opt out before the
 * download starts.
 *
 * Pressing Continue with the toggle on triggers the download via
 * [SettingsViewModel.setRouterEnabled] (idempotent — kicks the
 * RouterDownloadManager when not yet on disk, no-op when already
 * downloaded). The download runs in the background; the user can
 * keep going through onboarding without waiting.
 */
@Composable
fun RouterScreen(
    settingsViewModel: SettingsViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val state by settingsViewModel.state.collectAsStateWithLifecycle()

    OnboardingScaffold(
        title = stringResource(R.string.onboarding_router_title),
        currentStep = 7,
        onBack = onBack,
        onPrimary = {
            // Re-applying the toggle while it's already on is harmless:
            // setRouterEnabled either kicks the download (if not on
            // disk) or re-loads the model into the engine (if it is).
            // Either way the user gets to the next screen immediately
            // and the download proceeds in the background.
            if (state.routerEnabled) {
                settingsViewModel.setRouterEnabled(true)
            }
            onNext()
        },
    ) {
        Text(
            text = stringResource(R.string.onboarding_router_body),
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(16.dp))

        SkillRouterSection(
            enabled = state.routerEnabled,
            downloaded = state.routerDownloaded,
            downloadState = state.routerDownloadState,
            onToggle = settingsViewModel::setRouterEnabled,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_router_continue_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
