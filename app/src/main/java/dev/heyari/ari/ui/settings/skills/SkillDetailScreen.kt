package dev.heyari.ari.ui.settings.skills

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.material3.RichText
import dev.heyari.ari.R
import dev.heyari.ari.ui.components.AriTopBar
import uniffi.ari_ffi.FfiSkillManifest

/**
 * Skill detail view — used for both browse rows and installed rows.
 *
 * For installed skills we fetch the rich on-disk manifest (author,
 * homepage, capabilities, supported languages, full SKILL.md body) via
 * [SkillsViewModel.loadInstalledManifest]. For browse-only entries we
 * fall back to the fields the registry index carries on the
 * `FfiBrowseEntry` itself — which is less rich but still enough for the
 * user to decide whether to install.
 */
@Composable
fun SkillDetailScreen(
    skillId: String,
    source: String,
    onBack: () -> Unit,
    viewModel: SkillsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingUninstall by remember { mutableStateOf(false) }

    // Pull browse list if needed for browse-source deep links, and the
    // on-disk manifest for anything actually installed. Clear the cached
    // manifest on leave so the next detail screen doesn't briefly flash
    // the previous skill's data.
    LaunchedEffect(skillId) {
        if (source == "browse" && state.browse.isEmpty()) {
            viewModel.browse()
        }
    }
    val isInstalledLocally = state.installed.any { it.id == skillId } ||
        state.browse.firstOrNull { it.id == skillId }?.installed == true
    LaunchedEffect(skillId, isInstalledLocally) {
        if (isInstalledLocally) {
            viewModel.loadInstalledManifest(skillId)
        } else {
            viewModel.clearDetailManifest()
        }
    }
    DisposableEffect(skillId) {
        onDispose { viewModel.clearDetailManifest() }
    }

    val browseEntry = remember(state.browse, skillId) {
        state.browse.firstOrNull { it.id == skillId }
    }
    val manifest: FfiSkillManifest? = state.detailManifest?.takeIf { it.id == skillId }

    // Collapse manifest + browse entry into one view model so the render
    // path below doesn't care which source supplied each field. Manifest
    // wins when present; browse-entry fills the gaps (and, for browse-only
    // skills, provides everything the registry index carries).
    val view = remember(manifest, browseEntry, skillId) {
        SkillDetailView.from(manifest, browseEntry, skillId)
    }
    val busy = skillId in state.installingIds

    if (pendingUninstall) {
        AlertDialog(
            onDismissRequest = { pendingUninstall = false },
            title = { Text(stringResource(R.string.skills_uninstall_confirm_title)) },
            text = { Text(stringResource(R.string.skills_uninstall_confirm_message, view.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstall(skillId)
                    pendingUninstall = false
                    onBack()
                }) {
                    Text(stringResource(R.string.skills_uninstall_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = false }) {
                    Text(stringResource(R.string.skills_uninstall_confirm_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = { AriTopBar(title = view.title, onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = view.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (view.version.isNotBlank()) {
                Text(
                    text = stringResource(R.string.skills_detail_version, view.version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = skillId,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (view.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = view.description,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            if (state.detailManifestLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            if (view.hasAnyFacts) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                ManifestFacts(view = view)
            }

            if (view.body.isNotBlank()) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.skills_detail_about),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                // Render the SKILL.md body as GFM markdown via compose-richtext.
                // The material3 bridge picks up our MaterialTheme typography
                // and colour scheme automatically, so headings, inline code,
                // bullet / numbered lists, bold/italic, links, and tables all
                // come out theme-consistent without per-element styling here.
                RichText {
                    Markdown(content = view.body.trim())
                }
            }

            if (state.errorMessage != null) {
                Text(
                    text = state.errorMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    busy -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    view.installed -> OutlinedButton(onClick = { pendingUninstall = true }) {
                        Text(stringResource(R.string.skills_uninstall))
                    }
                    else -> Button(onClick = { viewModel.installById(skillId) }) {
                        Text(stringResource(R.string.skills_install))
                    }
                }
            }
        }
    }
}

/**
 * Merged view of the skill's metadata — pulls from the on-disk manifest
 * when an installed skill is open, and from the registry index entry
 * otherwise. Whatever's available gets surfaced; blank-after-merge means
 * the registry genuinely didn't carry it (e.g. an index row from before
 * the author/homepage fields were added).
 */
private data class SkillDetailView(
    val title: String,
    val version: String,
    val description: String,
    val author: String?,
    val homepage: String?,
    val license: String?,
    val capabilities: List<String>,
    val languages: List<String>,
    val body: String,
    val installed: Boolean,
) {
    val hasAnyFacts: Boolean
        get() = !author.isNullOrBlank() ||
            !homepage.isNullOrBlank() ||
            !license.isNullOrBlank() ||
            capabilities.isNotEmpty() ||
            languages.isNotEmpty()

    companion object {
        fun from(
            manifest: FfiSkillManifest?,
            browse: uniffi.ari_ffi.FfiBrowseEntry?,
            fallbackId: String,
        ): SkillDetailView {
            val title = manifest?.name?.takeIf { it.isNotBlank() }
                ?: browse?.name?.takeIf { it.isNotBlank() }
                ?: fallbackId
            val version = manifest?.version ?: browse?.version ?: ""
            val description = manifest?.description?.takeIf { it.isNotBlank() }
                ?: browse?.description.orEmpty()
            val installed = manifest != null || browse?.installed == true
            return SkillDetailView(
                title = title,
                version = version,
                description = description,
                author = manifest?.author ?: browse?.author,
                homepage = manifest?.homepage ?: browse?.homepage,
                license = manifest?.license ?: browse?.license,
                capabilities = manifest?.capabilities
                    ?: browse?.capabilities.orEmpty(),
                languages = manifest?.languages
                    ?: browse?.languages.orEmpty(),
                body = manifest?.body.orEmpty(),
                installed = installed,
            )
        }
    }
}

@Composable
private fun ManifestFacts(view: SkillDetailView) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        view.author?.takeIf { it.isNotBlank() }?.let {
            FactRow(label = stringResource(R.string.skills_detail_author), value = it)
        }
        view.homepage?.takeIf { it.isNotBlank() }?.let { homepage ->
            FactRow(
                label = stringResource(R.string.skills_detail_homepage),
                value = homepage,
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, homepage.toUri()))
                    }
                },
            )
        }
        view.license?.takeIf { it.isNotBlank() }?.let {
            FactRow(label = stringResource(R.string.skills_detail_license), value = it)
        }
        if (view.languages.isNotEmpty()) {
            FactRow(
                label = stringResource(R.string.skills_detail_languages),
                value = view.languages.joinToString(", "),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.skills_detail_capabilities),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (view.capabilities.isEmpty()) {
                Text(
                    text = stringResource(R.string.skills_detail_no_capabilities),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (cap in view.capabilities) {
                        AssistChip(onClick = {}, label = { Text(cap) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FactRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        modifier = if (onClick != null) {
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        } else {
            Modifier.fillMaxWidth()
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = if (onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier
            },
        )
    }
}
