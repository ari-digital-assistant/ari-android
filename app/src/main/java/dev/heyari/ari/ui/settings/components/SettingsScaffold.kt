package dev.heyari.ari.ui.settings.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import dev.heyari.ari.ui.components.AriTopBar

/**
 * Shared scaffold for every Settings subpage: back-arrow top bar with the
 * page title, and a body slot the caller fills in. Exists to stop every
 * subpage from reinventing the same TopAppBar block.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            AriTopBar(title = title, onBack = onBack)
        },
        content = content,
    )
}
