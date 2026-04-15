package dev.heyari.ari.ui.settings.skills

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
 * fetch the registry's preview manifest sidecar via
 * [SkillsViewModel.loadBrowseManifestPreview] so the user gets the same
 * full-body markdown view before deciding to install — no need to
 * commit to a download first. If the sidecar isn't available (older
 * index format) the screen falls back to the lightweight
 * [uniffi.ari_ffi.FfiBrowseEntry] fields.
 *
 * The install/uninstall action lives in the top bar's trailing slot so
 * it's always a thumb-tap away, no matter how long the markdown body
 * scrolls. Any long-running operation (install, manifest fetch) shows a
 * small spinner in that same slot so the primary action region is
 * always where the user expects to find feedback.
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
    // manifest for anything actually installed. Clear the cached manifest
    // on leave so the next detail screen doesn't briefly flash the
    // previous skill's data.
    LaunchedEffect(skillId) {
        if (source == "browse" && state.browse.isEmpty()) {
            viewModel.browse()
        }
    }
    val isInstalledLocally = state.installed.any { it.id == skillId } ||
        state.browse.firstOrNull { it.id == skillId }?.installed == true
    // Pick the right source for the rich manifest: local SKILL.md for
    // installed skills, registry preview sidecar for browse-only. Both
    // land in state.detailManifest, so the render path doesn't care.
    LaunchedEffect(skillId, isInstalledLocally) {
        if (isInstalledLocally) {
            viewModel.loadInstalledManifest(skillId)
        } else {
            viewModel.loadBrowseManifestPreview(skillId)
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
    val view = remember(manifest, browseEntry, skillId, isInstalledLocally) {
        SkillDetailView.from(manifest, browseEntry, skillId, isInstalledLocally)
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
        topBar = {
            AriTopBar(
                title = view.title,
                onBack = onBack,
                actions = {
                    InstallAction(
                        busy = busy,
                        installed = view.installed,
                        onInstall = { viewModel.installById(skillId) },
                        onUninstallRequest = { pendingUninstall = true },
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Subtitle: version + reverse-DNS id on one subdued line.
            // The top bar already shows the human name, so we don't repeat
            // it here — this row is just the machine-facts context.
            val subtitle = buildSubtitle(view.version, skillId)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (view.description.isNotBlank()) {
                Text(
                    text = view.description,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            // Facts card: either a loading placeholder (so the fallback
            // browse fields don't briefly flash while the richer preview
            // is fetching) or the populated facts, wrapped in a tonal
            // Surface for visual grouping.
            when {
                state.detailManifestLoading -> FactsLoadingCard()
                view.hasAnyFacts -> FactsCard(view = view)
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
        }
    }
}

/**
 * Top-bar trailing action: Install / Uninstall / spinner depending on
 * state. Kept as a private composable so the Scaffold call site above
 * stays readable.
 *
 * Install uses a filled-tonal button so it reads as the primary call-to-
 * action without fighting the top bar's own tonal background. Uninstall
 * is outlined — a destructive action sitting in an easy-to-tap corner
 * wants a quieter affordance, and the existing confirm dialog catches
 * any misfire anyway.
 */
@Composable
private fun InstallAction(
    busy: Boolean,
    installed: Boolean,
    onInstall: () -> Unit,
    onUninstallRequest: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        when {
            busy -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
            installed -> OutlinedButton(onClick = onUninstallRequest) {
                Text(stringResource(R.string.skills_uninstall))
            }
            else -> FilledTonalButton(onClick = onInstall) {
                Text(stringResource(R.string.skills_install))
            }
        }
    }
}

/**
 * Grouped facts block (author / homepage / licence / languages /
 * capabilities) rendered inside a tonal surface so it reads as one
 * cohesive card rather than a loose ladder of label/value pairs.
 */
@Composable
private fun FactsCard(view: SkillDetailView) {
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ManifestFacts(
            view = view,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * Skeleton stand-in for the facts card while the manifest is fetching.
 * Sized roughly to match the real card so the page doesn't jump when
 * the facts land.
 */
@Composable
private fun FactsLoadingCard() {
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

private fun buildSubtitle(version: String, skillId: String): String {
    val parts = mutableListOf<String>()
    if (version.isNotBlank()) parts.add(version)
    parts.add(skillId)
    return parts.joinToString(" · ")
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
            installed: Boolean,
        ): SkillDetailView {
            val title = manifest?.name?.takeIf { it.isNotBlank() }
                ?: browse?.name?.takeIf { it.isNotBlank() }
                ?: fallbackId
            val version = manifest?.version ?: browse?.version ?: ""
            val description = manifest?.description?.takeIf { it.isNotBlank() }
                ?: browse?.description.orEmpty()
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManifestFacts(view: SkillDetailView, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                // FlowRow wraps onto multiple rows if the chips don't fit
                // — skills with many capabilities used to overflow the
                // screen edge on narrow devices.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (cap in view.capabilities) {
                        AssistChip(onClick = {}, label = { Text(cap) })
                    }
                }
            }
        }
    }
}

/**
 * Two-column key/value row. Label is fixed-width on the left so the
 * values line up across rows; homepage gets the clickable affordance
 * and switches to the primary accent so it reads as a link.
 */
@Composable
private fun FactRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
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
