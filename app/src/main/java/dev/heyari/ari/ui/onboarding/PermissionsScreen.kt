package dev.heyari.ari.ui.onboarding

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.ui.settings.PermissionStatus
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.pages.PermissionsSection

@Composable
fun PermissionsScreen(
    settingsViewModel: SettingsViewModel,
    onboardingViewModel: OnboardingViewModel,
    onNext: () -> Unit,
    onNextMicDenied: () -> Unit,
    onBack: () -> Unit,
    onRequestRecordAudio: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenFsnSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val state by settingsViewModel.state.collectAsStateWithLifecycle()
    var showMissingPopup by rememberSaveable { mutableStateOf(false) }

    // Refresh permission state on every resume — same pattern as
    // PermissionsSettingsPage. Catches overlay/FSN grants that happen
    // in a separate Android settings activity.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settingsViewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    OnboardingScaffold(
        title = stringResource(R.string.onboarding_permissions_title),
        currentStep = 2,
        onBack = onBack,
        onPrimary = {
            val perms = state.permissions
            val anyRecommendedMissing = !perms.recordAudio || !perms.postNotifications || !perms.systemAlertWindow
            if (anyRecommendedMissing) {
                showMissingPopup = true
            } else {
                onNext()
            }
        },
    ) {
        Text(
            text = stringResource(R.string.onboarding_permissions_body),
            style = MaterialTheme.typography.bodyMedium,
        )

        PermissionsSection(
            permissions = state.permissions,
            onRequestRecordAudio = onRequestRecordAudio,
            onRequestNotifications = onRequestNotifications,
            onOpenFsnSettings = onOpenFsnSettings,
            onOpenOverlaySettings = onOpenOverlaySettings,
            onOpenAppSettings = onOpenAppSettings,
        )
    }

    if (showMissingPopup) {
        MissingPermissionsDialog(
            permissions = state.permissions,
            onStay = { showMissingPopup = false },
            onContinue = {
                showMissingPopup = false
                if (!state.permissions.recordAudio) {
                    onboardingViewModel.setMicDenied(true)
                    onNextMicDenied()
                } else {
                    onNext()
                }
            },
        )
    }
}

@Composable
private fun MissingPermissionsDialog(
    permissions: PermissionStatus,
    onStay: () -> Unit,
    onContinue: () -> Unit,
) {
    val lines = buildList {
        if (!permissions.recordAudio) add(stringResource(R.string.onboarding_permissions_missing_mic))
        if (!permissions.postNotifications) add(stringResource(R.string.onboarding_permissions_missing_notifications))
        if (!permissions.systemAlertWindow) add(stringResource(R.string.onboarding_permissions_missing_overlay))
    }

    AlertDialog(
        onDismissRequest = onStay,
        title = { Text(stringResource(R.string.onboarding_permissions_missing_title)) },
        text = {
            Text(lines.joinToString("\n\n"))
        },
        confirmButton = {
            TextButton(onClick = onStay) {
                Text(stringResource(R.string.onboarding_permissions_stay))
            }
        },
        dismissButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.onboarding_permissions_continue))
            }
        },
    )
}
