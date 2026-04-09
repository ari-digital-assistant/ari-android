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
 *  - Root mode ([onOpenMenu] supplied): centred Ari symbolic as the title,
 *    burger icon on the left that opens the navigation drawer.
 *  - Subpage mode ([onBack] supplied): text title, back-arrow on the left.
 *
 * [actions] lets callers drop trailing icons (e.g. the wake-word switch on
 * the conversation screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AriTopBar(
    title: String? = null,
    onOpenMenu: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    CenterAlignedTopAppBar(
        title = {
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
        },
        navigationIcon = {
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
        },
        actions = actions,
    )
}
