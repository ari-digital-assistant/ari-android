package dev.heyari.ari.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R
import dev.heyari.ari.updates.UpdatesPreferences
import kotlinx.coroutines.delay

/**
 * Top-of-conversation banner stack. Renders, in order:
 *   1. Thin progress banner while an Update All is in flight.
 *   2. Brief terminal "X of Y installed" line that auto-dismisses.
 *   3. Idle model banner (if pending).
 *   4. Idle skill banner (if pending).
 *
 * Both idle banners share a single surface treatment (low-tone elevated
 * surface, neutral on-surface text) and are distinguished only by their
 * leading icon and accent tint — so two stacked banners read as siblings
 * of one informational pattern rather than two clashing event panels.
 */
@Composable
fun UpdateBanners(
    state: UpdateBannerState,
    onApplyAllModels: () -> Unit,
    onApplyAllSkills: () -> Unit,
    onOpenModelDetails: () -> Unit,
    onOpenSkillDetails: () -> Unit,
    onDismissModels: () -> Unit,
    onDismissSkills: () -> Unit,
    onDismissTerminal: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.applying?.let { applying ->
            ProgressBanner(applying)
        }

        state.terminal?.let { terminal ->
            // Auto-dismiss after ~3s; the user can also tap to ack early.
            LaunchedEffect(terminal) {
                delay(3000)
                onDismissTerminal()
            }
            TerminalBanner(terminal, onDismiss = onDismissTerminal)
        }

        if (state.applying == null && state.terminal == null) {
            if (state.modelUpdates.isNotEmpty()) {
                IdleBanner(
                    icon = Icons.Default.SystemUpdate,
                    accent = MaterialTheme.colorScheme.primary,
                    title = pluralUpdates(state.modelUpdates.size, isModel = true),
                    body = state.modelUpdates.joinToString(", ") { it.displayName },
                    onApplyAll = onApplyAllModels,
                    onDetails = onOpenModelDetails,
                    onDismiss = onDismissModels,
                )
            }
            if (state.skillUpdates.isNotEmpty()) {
                IdleBanner(
                    icon = Icons.Default.Extension,
                    accent = MaterialTheme.colorScheme.tertiary,
                    title = pluralUpdates(state.skillUpdates.size, isModel = false),
                    body = state.skillUpdates.joinToString(", ") { it.displayName },
                    onApplyAll = onApplyAllSkills,
                    onDetails = onOpenSkillDetails,
                    onDismiss = onDismissSkills,
                )
            }
        }
    }
}

@Composable
private fun IdleBanner(
    icon: ImageVector,
    accent: Color,
    title: String,
    body: String,
    onApplyAll: () -> Unit,
    onDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier
                        .padding(top = 2.dp, end = 10.dp)
                        .size(18.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.update_banner_dismiss),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDetails) {
                    Text(stringResource(R.string.update_banner_details))
                }
                Spacer(Modifier.width(2.dp))
                TextButton(onClick = onApplyAll) {
                    Text(
                        text = stringResource(R.string.update_banner_update_all),
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressBanner(progress: ApplyingProgress) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val label = stringResource(
                if (progress.category == UpdatesPreferences.Category.MODEL) {
                    R.string.update_banner_progress_model
                } else {
                    R.string.update_banner_progress_skill
                },
                progress.currentDisplayName,
                progress.currentIndex,
                progress.totalCount,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Determinate when we have byte progress; indeterminate otherwise
            // (skill installs go via FFI, which doesn't surface bytes).
            if (progress.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = {
                        val itemFraction = progress.bytesSoFar.toFloat() /
                            progress.totalBytes.toFloat()
                        val completedItems = (progress.currentIndex - 1).coerceAtLeast(0)
                        val overall = (completedItems + itemFraction.coerceIn(0f, 1f)) /
                            progress.totalCount.toFloat()
                        overall.coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
        }
    }
}

@Composable
private fun TerminalBanner(terminal: TerminalMessage, onDismiss: () -> Unit) {
    val total = terminal.successCount + terminal.failCount
    val isAllSuccess = terminal.failCount == 0 && terminal.successCount > 0
    val accent = if (isAllSuccess) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(8.dp),
            ) {
                Surface(
                    modifier = Modifier.size(8.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = accent,
                    content = {},
                )
            }
            val message = if (isAllSuccess) {
                stringResource(R.string.update_banner_terminal_all_success, terminal.successCount)
            } else if (terminal.successCount == 0) {
                stringResource(R.string.update_banner_terminal_all_failed, total)
            } else {
                stringResource(
                    R.string.update_banner_terminal_partial,
                    terminal.successCount,
                    total,
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.update_banner_dismiss),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun pluralUpdates(count: Int, isModel: Boolean): String =
    if (isModel) {
        if (count == 1) stringResource(R.string.update_banner_model_one)
        else stringResource(R.string.update_banner_model_many, count)
    } else {
        if (count == 1) stringResource(R.string.update_banner_skill_one)
        else stringResource(R.string.update_banner_skill_many, count)
    }
