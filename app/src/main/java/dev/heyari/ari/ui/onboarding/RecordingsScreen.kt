package dev.heyari.ari.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.ui.settings.RecordingsViewModel
import dev.heyari.ari.ui.settings.pages.WhatToRecordSection
import dev.heyari.ari.ui.settings.pages.WhatToShareSection

/**
 * The last thing the wizard asks: whether to keep any recordings, and whether
 * to contribute them. Everything defaults to off, so tapping Next without
 * reading a word leaves the user recording nothing and sharing nothing.
 *
 * Same sections as Settings > Developer, deliberately — the wizard must not be
 * the place that describes these switches differently. The contributor code
 * and the deletion button are the page's alone: neither means anything until
 * something has actually been shared.
 */
@Composable
fun RecordingsScreen(
    onNext: () -> Unit,
    onBack: () -> Unit,
    viewModel: RecordingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    OnboardingScaffold(
        title = stringResource(R.string.onboarding_recordings_title),
        currentStep = 9,
        onBack = onBack,
        onPrimary = onNext,
    ) {
        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_recordings_blurb),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        WhatToRecordSection(
            keep = state.keep,
            onKeepWake = viewModel::setKeepWake,
            onKeepFalseTrigger = viewModel::setKeepFalseTrigger,
            onKeepCommand = viewModel::setKeepCommand,
            onKeepEverything = viewModel::setKeepEverything,
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        WhatToShareSection(
            keep = state.keep,
            share = state.share,
            onShareWake = viewModel::setShareWake,
            onShareFalseTrigger = viewModel::setShareFalseTrigger,
            onShareCommand = viewModel::setShareCommand,
            onShareEverything = viewModel::setShareEverything,
        )
    }
}
