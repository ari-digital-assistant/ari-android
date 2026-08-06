package dev.heyari.ari.ui.settings.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import dev.heyari.ari.ui.components.AriTopBar

/**
 * Shared scaffold for every Settings subpage: back-arrow top bar with the
 * page title, and a body slot the caller fills in. Exists to stop every
 * subpage from reinventing the same TopAppBar block.
 *
 * [actions] is for the rare subpage that needs a trailing action alongside
 * back — a full-screen editor's Save button, say — where a bottom button
 * would fight the map/list content for space. Empty by default, same as
 * [AriTopBar]'s own slot it forwards to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            AriTopBar(title = title, onBack = onBack, actions = actions)
        },
        content = content,
    )
}
