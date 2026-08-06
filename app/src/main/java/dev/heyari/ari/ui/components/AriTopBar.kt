package dev.heyari.ari.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R

/**
 * The app's shared top bar. Two modes:
 *
 *  - Root mode ([onOpenMenu] supplied): Ari symbolic as the title, burger icon
 *    on the left that opens the navigation drawer.
 *  - Subpage mode ([onBack] supplied): text title, back-arrow on the left.
 *
 * [actions] lets callers drop trailing content — a single icon in most
 * subpages, but the conversation screen's top bar carries the full
 * [dev.heyari.ari.ui.conversation.ListeningModeSwitch], a three-segment
 * control with real width.
 *
 * Root mode uses a plain, start-aligned [TopAppBar] rather than
 * [CenterAlignedTopAppBar] for exactly that reason: a centre-aligned bar
 * centres its title on the bar's full width regardless of how wide the
 * actions are, so a wide action collides with a logo pinned dead-centre —
 * Material's own guidance limits [CenterAlignedTopAppBar] to a single small
 * action icon. Putting the logo right after the menu button instead means the
 * logo and the actions never fight over the same space. Subpage mode keeps
 * [CenterAlignedTopAppBar]: a back arrow against a short text title (plus,
 * at most, a single Save action) doesn't have this problem.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AriTopBar(
    title: String? = null,
    onOpenMenu: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val titleContent: @Composable () -> Unit = {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_ari_symbolic),
                contentDescription = stringResource(R.string.top_bar_app_icon_description),
                modifier = Modifier.size(32.dp),
                colorFilter = ColorFilter.tint(LocalContentColor.current),
            )
        }
    }
    val navigationIconContent: @Composable () -> Unit = {
        when {
            onOpenMenu != null -> IconButton(onClick = onOpenMenu) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(R.string.top_bar_open_menu),
                )
            }
            onBack != null -> IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.top_bar_back),
                )
            }
        }
    }

    if (onOpenMenu != null) {
        TopAppBar(
            title = titleContent,
            navigationIcon = navigationIconContent,
            actions = actions,
        )
    } else {
        CenterAlignedTopAppBar(
            title = titleContent,
            navigationIcon = navigationIconContent,
            actions = actions,
        )
    }
}
