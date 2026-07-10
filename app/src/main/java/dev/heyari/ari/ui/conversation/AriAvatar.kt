package dev.heyari.ari.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R

/**
 * Small circular "Ari" mark used to badge assistant message bubbles.
 * Tinted [MaterialTheme.colorScheme.primaryContainer]/[MaterialTheme.colorScheme.onPrimaryContainer]
 * so it follows dynamic Material You theming.
 */
@Composable
fun AriAvatar(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ari_symbolic),
            contentDescription = stringResource(R.string.top_bar_app_icon_description),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(size).padding(size * 0.18f),
        )
    }
}
