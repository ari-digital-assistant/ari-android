package dev.heyari.ari.ui.settings.skills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.ui.components.AriTopBar
import java.time.Duration
import java.time.Instant

@Composable
fun SkillsScreen(
    onBack: () -> Unit,
    onOpenDetail: (id: String, source: String) -> Unit,
    viewModel: SkillsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Auto-refresh each tab when it becomes visible, per wtf.md.
    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            0 -> viewModel.checkForUpdates()
            1 -> viewModel.browse()
        }
    }

    Scaffold(
        topBar = {
            AriTopBar(
                title = stringResource(R.string.skills_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.skills_tab_installed)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.skills_tab_browse)) },
                )
            }

            when (selectedTab) {
                0 -> InstalledTab(state, viewModel, onOpenDetail)
                else -> BrowseTab(state, viewModel, onOpenDetail)
            }
        }
    }
}

@Composable
private fun InstalledTab(
    state: SkillsScreenState,
    viewModel: SkillsViewModel,
    onOpenDetail: (id: String, source: String) -> Unit,
) {
    var pendingUninstall by remember { mutableStateOf<String?>(null) }

    pendingUninstall?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text(stringResource(R.string.skills_uninstall_confirm_title)) },
            text = { Text(stringResource(R.string.skills_uninstall_confirm_message, id)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstall(id)
                    pendingUninstall = null
                }) {
                    Text(stringResource(R.string.skills_uninstall_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text(stringResource(R.string.skills_uninstall_confirm_cancel))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = lastCheckedLabel(state.lastCheckedInstalled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = viewModel::checkForUpdates, enabled = !state.checking) {
                if (state.checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.skills_refresh))
                }
            }
        }

        if (state.errorMessage != null) {
            ErrorCard(state.errorMessage)
        }

        if (state.updates.isNotEmpty()) {
            UpdatesBanner(
                updates = state.updates,
                installingIds = state.installingIds,
                onInstall = viewModel::installUpdate,
            )
        }

        if (state.installed.isEmpty()) {
            Text(
                text = stringResource(R.string.skills_empty_installed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (skill in state.installed) {
                InstalledRow(
                    id = skill.id,
                    version = skill.version,
                    onClick = { onOpenDetail(skill.id, "installed") },
                    onUninstall = { pendingUninstall = skill.id },
                    busy = skill.id in state.installingIds,
                )
            }
        }
    }
}

@Composable
private fun InstalledRow(
    id: String,
    version: String,
    onClick: () -> Unit,
    onUninstall: () -> Unit,
    busy: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = id,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.skills_detail_version, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = onUninstall) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.skills_uninstall),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdatesBanner(
    updates: List<uniffi.ari_ffi.FfiSkillUpdate>,
    installingIds: Set<String>,
    onInstall: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${updates.size} update${if (updates.size == 1) "" else "s"} available",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            for (update in updates) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = update.name.ifBlank { update.id },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(
                            text = "${update.installedVersion} → ${update.availableVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    val installing = update.id in installingIds
                    Button(onClick = { onInstall(update.id) }, enabled = !installing) {
                        if (installing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(stringResource(R.string.skills_install))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseTab(
    state: SkillsScreenState,
    viewModel: SkillsViewModel,
    onOpenDetail: (id: String, source: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = lastCheckedLabel(state.lastCheckedBrowse),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = viewModel::browse, enabled = !state.browsing) {
                if (state.browsing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.skills_refresh))
                }
            }
        }

        OutlinedTextField(
            value = state.browseQuery,
            onValueChange = viewModel::setBrowseQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.skills_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )

        if (state.errorMessage != null) {
            ErrorCard(state.errorMessage)
        }

        val filtered = remember(state.browse, state.browseQuery) {
            if (state.browseQuery.isBlank()) {
                state.browse
            } else {
                state.browse.filter {
                    it.name.contains(state.browseQuery, ignoreCase = true) ||
                        it.id.contains(state.browseQuery, ignoreCase = true)
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.browsing && state.browse.isEmpty() -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                filtered.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.skills_empty_browse),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    for (entry in filtered) {
                        BrowseRow(
                            entry = entry,
                            onClick = { onOpenDetail(entry.id, "browse") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseRow(
    entry: uniffi.ari_ffi.FfiBrowseEntry,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name.ifBlank { entry.id },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "v${entry.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                )
            }
            if (entry.installed) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = stringResource(R.string.skills_detail_installed_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun lastCheckedLabel(instant: Instant?): String {
    if (instant == null) return stringResource(R.string.skills_last_checked_never)
    val elapsed = Duration.between(instant, Instant.now())
    val minutes = elapsed.toMinutes()
    return when {
        minutes < 1 -> stringResource(R.string.skills_last_checked_just_now)
        minutes < 60 -> stringResource(R.string.skills_last_checked_minutes, minutes.toInt())
        elapsed.toHours() < 24 -> stringResource(R.string.skills_last_checked_hours, elapsed.toHours().toInt())
        else -> stringResource(R.string.skills_last_checked_days, elapsed.toDays().toInt())
    }
}
