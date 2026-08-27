package dev.heyari.ari.ui.settings.skills

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.material3.RichText
import dev.heyari.ari.R
import dev.heyari.ari.assistant.openDefaultAssistantSettings
import dev.heyari.ari.media.hasNotificationAccess
import dev.heyari.ari.reporting.ReportKind
import dev.heyari.ari.ui.conversation.ReportDialog
import dev.heyari.ari.media.openNotificationListenerSettings
import dev.heyari.ari.skills.SkillScreenshotCache
import dev.heyari.ari.ui.components.AriTopBar
import dev.heyari.ari.ui.components.SkillSettingsPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.ari_ffi.FfiConfigField
import uniffi.ari_ffi.FfiSettingsQueryResult
import uniffi.ari_ffi.FfiSkillManifest
import java.io.File

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
    /**
     * Fires once when the user installs this skill from a browse-source
     * detail view (i.e. arrived here via Browse, then tapped Install).
     * The NavHost wires this to drop a one-shot signal on the previous
     * back stack entry's SavedStateHandle so [SkillsScreen] can switch
     * to the Installed tab on resume — saving the user from being
     * dumped back into the Browse list when their intent is now clearly
     * "go look at my installed skills".
     */
    onJustInstalledFromBrowse: () -> Unit = {},
    viewModel: SkillsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingUninstall by remember { mutableStateOf(false) }
    var pendingLocaleMismatchInstall by remember { mutableStateOf(false) }
    // Remember whether this skill was already installed when we arrived
    // — only fire the "just installed from browse" signal on a real
    // not-installed → installed transition, never for skills the user
    // tapped in via Installed tab in the first place.
    val wasInstalledOnEntry = remember(skillId) {
        state.installed.any { it.id == skillId } ||
            state.browse.firstOrNull { it.id == skillId }?.installed == true
    }
    var firedJustInstalled by remember(skillId) { mutableStateOf(false) }

    // Pull browse list if needed for browse-source deep links, and the
    // manifest for anything actually installed. Clear the cached manifest
    // on leave so the next detail screen doesn't briefly flash the
    // previous skill's data.
    LaunchedEffect(skillId) {
        if (source == "browse" && state.browse.isEmpty()) {
            viewModel.browse()
        }
        // Detail screen has its own VM (sibling composables in the
        // NavHost), so its state.updates list starts empty even if the
        // Skills tab fetched one. Refresh on land so the Update button
        // shows up correctly without the user having to bounce out and
        // back.
        if (source == "installed" && state.updates.isEmpty()) {
            viewModel.checkForUpdates()
        }
    }
    val isInstalledLocally = state.installed.any { it.id == skillId } ||
        state.browse.firstOrNull { it.id == skillId }?.installed == true
    // Fire the "switch to Installed tab" signal exactly once, the moment
    // we observe the install completing. Guarded by source == "browse"
    // (so re-entries from Installed tab never trip it) and by a saved-
    // state-backed flag (so a process restart between install and back
    // doesn't replay the jump).
    LaunchedEffect(isInstalledLocally) {
        if (source == "browse" &&
            isInstalledLocally &&
            !wasInstalledOnEntry &&
            !firedJustInstalled
        ) {
            firedJustInstalled = true
            onJustInstalledFromBrowse()
        }
    }
    // Pick the right source for the rich manifest: local SKILL.md for
    // installed skills, registry preview sidecar for browse-only. Both
    // land in state.detailManifest, so the render path doesn't care.
    LaunchedEffect(skillId, isInstalledLocally) {
        if (isInstalledLocally) {
            viewModel.loadInstalledManifest(skillId)
            viewModel.loadSkillSettings(skillId)
        } else {
            viewModel.loadBrowseManifestPreview(skillId)
        }
    }
    // Screenshots come from the registry either way — they're never in the
    // bundle — so this doesn't care whether the skill is installed.
    LaunchedEffect(skillId) {
        viewModel.loadScreenshots(skillId)
    }
    DisposableEffect(skillId) {
        onDispose {
            viewModel.clearDetailManifest()
            viewModel.clearSkillSettings()
            viewModel.clearScreenshots()
        }
    }

    val browseEntry = remember(state.browse, skillId) {
        state.browse.firstOrNull { it.id == skillId }
    }
    val manifest: FfiSkillManifest? = state.detailManifest?.takeIf { it.id == skillId }

    // Collapse manifest + browse entry into one view model so the render
    // path below doesn't care which source supplied each field. Manifest
    // wins when present; browse-entry fills the gaps (and, for browse-only
    // skills, provides everything the registry index carries).
    val view = remember(manifest, browseEntry, skillId, isInstalledLocally, state.activeLocale) {
        SkillDetailView.from(manifest, browseEntry, skillId, isInstalledLocally, state.activeLocale)
    }
    val pendingUpdate = remember(state.updates, skillId) {
        state.updates.firstOrNull { it.id == skillId }
    }
    val busy = skillId in state.installingIds

    // Skill-install-time permission request, driven by
    // [CAPABILITY_PERMISSIONS]. A skill's declared capabilities are asked
    // for at the honest moment of consent — when the user installs it —
    // rather than up front in the first-run wizard. The install proceeds
    // whatever the user decides; every mapped capability degrades
    // gracefully, so a refusal costs a feature, not the installation.
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.installById(skillId) }
    fun startInstall() {
        val missing = missingPermissionsFor(view.capabilities, context, viewModel.isDefaultAssistant())
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            viewModel.installById(skillId)
        }
    }

    // Post-install nudge for skills that emit critical full-takeover alerts
    // (the `critical_alert` capability — timer and friends). Those alerts
    // reach the user over the locked screen via Android's full-screen
    // intent, which on API 34+ is a special-access permission the user must
    // grant in system settings. Rather than nag every first-run user in the
    // wizard, ask once — gently — right after they install a skill that
    // actually needs it. Capability-driven, never keyed on a skill id, and
    // fires on the real not-installed → installed transition only.
    var pendingFsnNudge by remember(skillId) { mutableStateOf(false) }
    var fsnNudgeResolved by remember(skillId) { mutableStateOf(false) }
    LaunchedEffect(isInstalledLocally, view.capabilities) {
        if (isInstalledLocally && !wasInstalledOnEntry && !fsnNudgeResolved &&
            view.capabilities.any { it.equals("critical_alert", ignoreCase = true) }
        ) {
            fsnNudgeResolved = true
            if (!canUseFullScreenIntent(context)) pendingFsnNudge = true
        }
    }

    if (pendingFsnNudge) {
        AlertDialog(
            onDismissRequest = { pendingFsnNudge = false },
            title = { Text(stringResource(R.string.skills_fsn_nudge_title)) },
            text = { Text(stringResource(R.string.skills_fsn_nudge_message, view.title)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingFsnNudge = false
                    openFullScreenIntentSettings(context)
                }) {
                    Text(stringResource(R.string.skills_fsn_nudge_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingFsnNudge = false }) {
                    Text(stringResource(R.string.skills_fsn_nudge_dismiss))
                }
            },
        )
    }

    var reportingSkill by remember(skillId) { mutableStateOf(false) }
    if (reportingSkill) {
        ReportDialog(
            reportedText = "${view.title} (${skillId})",
            prompt = null,
            skillId = skillId,
            kind = ReportKind.SKILL,
            onDismiss = { reportingSkill = false },
            onSend = { report ->
                reportingSkill = false
                viewModel.sendReport(report)
            },
        )
    }

    // Post-install nudge for skills that can send a message themselves (the
    // `send_message` capability). Sending without anybody tapping is only
    // permitted while Ari is the user's default assistant, so a skill installed
    // without that role works — it just hands the message to the messaging app
    // instead. Say so once, here, rather than letting the user discover it the
    // first time they ask for a text with their hands full.
    var pendingAssistantNudge by remember(skillId) { mutableStateOf(false) }
    var assistantNudgeResolved by remember(skillId) { mutableStateOf(false) }
    LaunchedEffect(isInstalledLocally, view.capabilities) {
        if (isInstalledLocally && !wasInstalledOnEntry && !assistantNudgeResolved &&
            view.capabilities.any { it.equals("send_message", ignoreCase = true) }
        ) {
            assistantNudgeResolved = true
            if (!viewModel.isDefaultAssistant()) pendingAssistantNudge = true
        }
    }

    if (pendingAssistantNudge) {
        AlertDialog(
            onDismissRequest = { pendingAssistantNudge = false },
            title = { Text(stringResource(R.string.skills_assistant_nudge_title)) },
            text = { Text(stringResource(R.string.skills_assistant_nudge_message, view.title)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingAssistantNudge = false
                    openDefaultAssistantSettings(context)
                }) {
                    Text(stringResource(R.string.skills_assistant_nudge_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAssistantNudge = false }) {
                    Text(stringResource(R.string.skills_assistant_nudge_dismiss))
                }
            },
        )
    }

    // Post-install nudge for skills that control media (the `media_control`
    // capability — music and friends). Transport needs Notification access, a
    // special-access grant reached only via system settings. Ask once, right
    // after installing a skill that needs it. Capability-driven, never keyed on
    // a skill id, fires on the real not-installed -> installed transition only.
    var pendingMediaNudge by remember(skillId) { mutableStateOf(false) }
    var mediaNudgeResolved by remember(skillId) { mutableStateOf(false) }
    LaunchedEffect(isInstalledLocally, view.capabilities) {
        if (isInstalledLocally && !wasInstalledOnEntry && !mediaNudgeResolved &&
            view.capabilities.any { it.equals("media_control", ignoreCase = true) }
        ) {
            mediaNudgeResolved = true
            if (!hasNotificationAccess(context)) pendingMediaNudge = true
        }
    }

    if (pendingMediaNudge) {
        AlertDialog(
            onDismissRequest = { pendingMediaNudge = false },
            title = { Text(stringResource(R.string.skills_media_nudge_title)) },
            text = { Text(stringResource(R.string.skills_media_nudge_message, view.title)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingMediaNudge = false
                    openNotificationListenerSettings(context)
                }) {
                    Text(stringResource(R.string.skills_media_nudge_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMediaNudge = false }) {
                    Text(stringResource(R.string.skills_media_nudge_dismiss))
                }
            },
        )
    }

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

    // Post-install assistant prompt — fires after a successful install
    // of a `type: assistant` skill when the user has no active
    // assistant configured. The ViewModel detects the condition and
    // sets `pendingAssistantPromptId`/`Name`; we render the dialog
    // here. Cleared either by `confirmPendingAssistantPrompt` (which
    // activates the assistant via the same path Settings uses) or
    // `dismissPendingAssistantPrompt` (which just clears the fields).
    if (state.pendingAssistantPromptId != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPendingAssistantPrompt() },
            title = { Text(stringResource(R.string.skills_set_as_default_assistant_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.skills_set_as_default_assistant_message,
                        state.pendingAssistantPromptName,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPendingAssistantPrompt() }) {
                    Text(stringResource(R.string.skills_set_as_default_assistant_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPendingAssistantPrompt() }) {
                    Text(stringResource(R.string.skills_set_as_default_assistant_dismiss))
                }
            },
        )
    }

    if (pendingLocaleMismatchInstall) {
        // Skill doesn't list the user's active language. Per wtf.md
        // Phase 6: install is still allowed, but force a one-time
        // confirmation so the user knows the skill will respond in
        // a language they haven't picked. No "don't ask again" — the
        // decision is per-install on purpose.
        AlertDialog(
            onDismissRequest = { pendingLocaleMismatchInstall = false },
            title = { Text(stringResource(R.string.skills_install_locale_mismatch_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.skills_install_locale_mismatch_message,
                        displayLanguageName(state.activeLocale),
                        view.languages.joinToString(", ") { it.uppercase() }.ifBlank { "—" },
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    startInstall()
                    pendingLocaleMismatchInstall = false
                }) {
                    Text(stringResource(R.string.skills_install_locale_mismatch_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLocaleMismatchInstall = false }) {
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
                    // Reporting a listing is what stops a skill registry
                    // reading as an unmoderated app store. In the top bar
                    // rather than the facts card because a skill with no
                    // metadata still needs to be reportable.
                    IconButton(onClick = { reportingSkill = true }) {
                        Icon(
                            imageVector = Icons.Filled.Flag,
                            contentDescription = stringResource(R.string.skills_report_action),
                        )
                    }
                    InstallAction(
                        busy = busy,
                        installed = view.installed,
                        availableUpdateVersion = pendingUpdate?.availableVersion,
                        onInstall = {
                            // Gate installs of language-mismatched skills behind
                            // a confirmation. Skills with no declared languages
                            // are treated as universal — no prompt.
                            val supports = view.languages.isEmpty() ||
                                view.languages.any { it.equals(state.activeLocale, ignoreCase = true) }
                            if (supports) {
                                startInstall()
                            } else {
                                pendingLocaleMismatchInstall = true
                            }
                        },
                        onUpdate = { viewModel.installUpdate(skillId) },
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
                if (view.englishFallback) {
                    Text(
                        text = stringResource(R.string.skills_browse_in_english_fallback),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // Straight after the description: "what does this actually look
            // like" is the next question a browsing user asks, and it's the
            // one that decides the install.
            if (state.detailScreenshots.isNotEmpty()) {
                ScreenshotStrip(
                    urls = state.detailScreenshots,
                    skillName = view.title,
                    cache = viewModel.screenshotCache,
                )
            }

            // Two layout flavours:
            //   - Installed: settings inline (always visible since they're
            //     the thing the user opens this screen to tweak), then a
            //     collapsible "Skill detail" wrapping the facts card +
            //     about body. Collapsed by default — once installed,
            //     description + settings are usually all you need.
            //   - Browse: facts card and about body inline as before, no
            //     settings (nothing to configure on a not-yet-installed
            //     skill).
            if (view.installed) {
                val hasSettingsSection =
                    state.detailSettings.isNotEmpty() || state.detailSettingsLoading
                if (hasSettingsSection) {
                    SettingsSection(
                        loading = state.detailSettingsLoading,
                        fields = state.detailSettings,
                        onValueChange = { key, value, isSecret ->
                            viewModel.setSkillSetting(skillId, key, value, isSecret)
                        },
                        querySkillSetting = { field, values ->
                            viewModel.querySkillSetting(skillId, field, values)
                        },
                        settingsAction = { action, values ->
                            viewModel.settingsAction(skillId, action, values)
                        },
                    )
                }
                // No settings to compete with → no reason to hide the
                // detail behind a tap. Expand by default so the screen
                // doesn't feel like an empty shell.
                CollapsibleSkillDetail(
                    view = view,
                    manifestLoading = state.detailManifestLoading,
                    initiallyExpanded = !hasSettingsSection,
                )
            } else {
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
            }

            state.errorMessage?.let { err ->
                Text(
                    text = skillErrorText(err),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Horizontally scrolling strip of the skill's preview screenshots, with a
 * full-screen viewer on tap.
 *
 * Screenshots are tall and phones are narrow, so a wrapped grid would push
 * everything else off the screen — the strip keeps the description, facts
 * and install button where the user left them. Each image loads
 * independently, so a slow or missing one never holds up the others.
 */
@Composable
private fun ScreenshotStrip(
    urls: List<String>,
    skillName: String,
    cache: SkillScreenshotCache,
) {
    var zoomed by remember(urls) { mutableStateOf<String?>(null) }

    zoomed?.let { url ->
        Dialog(
            onDismissRequest = { zoomed = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable { zoomed = null },
                contentAlignment = Alignment.Center,
            ) {
                ScreenshotImage(
                    url = url,
                    cache = cache,
                    contentDescription = stringResource(
                        R.string.skills_detail_screenshot_of,
                        skillName,
                    ),
                    decodeWidthPx = SCREENSHOT_VIEWER_WIDTH_PX,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.skills_detail_screenshots),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (url in urls) {
                Surface(
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.clickable { zoomed = url },
                ) {
                    ScreenshotImage(
                        url = url,
                        cache = cache,
                        contentDescription = stringResource(
                            R.string.skills_detail_screenshot_of,
                            skillName,
                        ),
                        decodeWidthPx = SCREENSHOT_THUMB_WIDTH_PX,
                        modifier = Modifier.height(SCREENSHOT_STRIP_HEIGHT),
                    )
                }
            }
        }
    }
}

/**
 * One screenshot, fetched through [SkillScreenshotCache] and decoded no
 * larger than [decodeWidthPx] needs.
 *
 * The downsampling isn't optional politeness. A phone screenshot is around
 * 1080x2400, which is a 10 MB bitmap, and the strip composes every shot at
 * once — three of them at full size is 30 MB held to render thumbnails a
 * finger wide. The registry caps screenshots by file size, not by
 * dimensions, so a well-compressed 4000px-wide image is a legal upload and
 * would be an OOM without this.
 *
 * Renders a placeholder box while loading, and keeps that box if the fetch
 * or decode fails. Screenshots are decoration; a broken one must never be
 * louder than the skill it's decorating.
 */
@Composable
private fun ScreenshotImage(
    url: String,
    cache: SkillScreenshotCache,
    contentDescription: String,
    decodeWidthPx: Int,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url, decodeWidthPx) {
        val file = cache.fetch(url) ?: return@produceState
        value = withContext(Dispatchers.IO) { decodeDownsampled(file, decodeWidthPx) }
    }
    val shown = bitmap
    if (shown == null) {
        Box(
            modifier = modifier
                .width(SCREENSHOT_PLACEHOLDER_WIDTH)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    } else {
        Image(
            bitmap = shown,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    }
}

/**
 * Decode [file] at the smallest power-of-two reduction that still leaves it
 * at least [reqWidthPx] wide. Two passes: bounds-only first to learn the
 * real size, then the real decode.
 */
private fun decodeDownsampled(file: File, reqWidthPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= reqWidthPx) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
}

/** Strip height — tall enough to read a phone screenshot's layout at a glance. */
private val SCREENSHOT_STRIP_HEIGHT = 320.dp

/** Width of the placeholder box standing in for a not-yet-decoded shot. */
private val SCREENSHOT_PLACEHOLDER_WIDTH = 148.dp

/**
 * Decode target for a thumbnail in the strip. They render around 150dp
 * wide, so this is generous even on a 4x-density screen, and it keeps a
 * typical phone screenshot down to a quarter of its full bitmap size.
 */
private const val SCREENSHOT_THUMB_WIDTH_PX = 450

/**
 * Decode target for the full-screen viewer — above any phone's width, so a
 * screenshot taken on a phone shows at its native resolution rather than
 * being upscaled into mush.
 */
private const val SCREENSHOT_VIEWER_WIDTH_PX = 1200

/**
 * Always-visible settings card for installed skills. Wraps the shared
 * [SkillSettingsPanel] in a tonal Surface (matching the facts card
 * styling) plus a "Settings" header so the section reads as a
 * deliberate first-class part of the page rather than a loose form.
 *
 * Shown for any installed skill that declares one or more entries in
 * its `metadata.ari.settings` schema. Skills with no settings simply
 * don't get this section — the screen still has the description and
 * the collapsible detail below it.
 */
@Composable
private fun SettingsSection(
    loading: Boolean,
    fields: List<FfiConfigField>,
    onValueChange: (key: String, value: String, isSecret: Boolean) -> Unit,
    querySkillSetting: suspend (field: String, values: Map<String, String>) -> FfiSettingsQueryResult,
    settingsAction: suspend (action: String, values: Map<String, String>) -> FfiSettingsQueryResult,
) {
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.skills_detail_settings),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                SkillSettingsPanel(
                    fields = fields,
                    onValueChange = onValueChange,
                    querySkillSetting = querySkillSetting,
                    settingsAction = settingsAction,
                )
            }
        }
    }
}

/**
 * Collapsible "Skill detail" wrapping the facts card + about body.
 * Collapsed by default for installed skills — once you've installed
 * something, the manifest body is reference material rather than
 * decision material, and shouldn't push the settings off the screen.
 */
@Composable
private fun CollapsibleSkillDetail(
    view: SkillDetailView,
    manifestLoading: Boolean,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.skills_detail_skill_detail),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }
        }
        if (expanded) {
            when {
                manifestLoading -> FactsLoadingCard()
                view.hasAnyFacts -> FactsCard(view = view)
            }
            if (view.body.isNotBlank()) {
                Text(
                    text = stringResource(R.string.skills_detail_about),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                RichText {
                    Markdown(content = view.body.trim())
                }
            }
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
    availableUpdateVersion: String?,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onUninstallRequest: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            busy -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
            installed -> {
                // Update button takes the primary call-to-action slot when
                // there's a newer version available; uninstall stays as a
                // quieter outlined affordance to its right.
                if (availableUpdateVersion != null) {
                    FilledTonalButton(onClick = onUpdate) {
                        Text(
                            stringResource(
                                R.string.skills_update_to_version,
                                availableUpdateVersion,
                            ),
                        )
                    }
                }
                OutlinedButton(onClick = onUninstallRequest) {
                    Text(stringResource(R.string.skills_uninstall))
                }
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
 * Android runtime permissions each declared capability needs, requested when
 * a skill is installed.
 *
 * Capability-driven and never keyed on a skill id, so the frontend owns the
 * capability → permission mapping and skills stay portable across frontends.
 * Only capabilities that degrade gracefully belong here — the install is not
 * blocked on the grant, so anything that would be broken rather than merely
 * diminished by a refusal needs a different flow.
 */
internal val CAPABILITY_PERMISSIONS: Map<String, List<String>> = mapOf(
    "location" to listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
    "contacts" to listOf(Manifest.permission.READ_CONTACTS),
    "send_message" to listOf(Manifest.permission.SEND_SMS),
)

/**
 * Every permission [capabilities] imply, deduplicated. Unknown capability
 * names contribute nothing, so a skill declaring something this frontend
 * doesn't map still installs.
 *
 * Kept free of [Context] so the mapping can be tested directly — the grant
 * check lives in [missingPermissionsFor].
 */
internal fun permissionsFor(capabilities: List<String>): List<String> =
    capabilities
        .flatMap { CAPABILITY_PERMISSIONS[it.lowercase()].orEmpty() }
        .distinct()

/**
 * The permissions [capabilities] need that aren't granted yet. Empty when
 * there's nothing to ask for, which is the common case.
 */
private fun missingPermissionsFor(
    capabilities: List<String>,
    context: Context,
    isDefaultAssistant: Boolean,
): List<String> =
    requestablePermissions(capabilities, isDefaultAssistant).filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

/**
 * Permissions Play only lets Ari hold as the registered default assistant, and
 * which it forbids us from even prompting for until we are.
 */
internal val ASSISTANT_ROLE_GATED: Set<String> = setOf(Manifest.permission.SEND_SMS)

/**
 * What may actually be asked for right now. A skill declaring `send_message`
 * still installs when Ari isn't the default assistant — SMS simply behaves like
 * every other service and opens the messaging app — so the permission is
 * dropped from the request rather than blocking anything.
 */
internal fun requestablePermissions(
    capabilities: List<String>,
    isDefaultAssistant: Boolean,
): List<String> =
    permissionsFor(capabilities).filter { isDefaultAssistant || it !in ASSISTANT_ROLE_GATED }

/**
 * Whether full-screen-intent alerts can currently fire. Below API 34 the
 * permission is granted at install, so it's always usable; from API 34 it's
 * a special-access grant the user controls.
 */
private fun canUseFullScreenIntent(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    } else {
        true
    }

/** Deep-link to the system "Full screen notifications" special-access page. */
private fun openFullScreenIntentSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
        data = "package:${context.packageName}".toUri()
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
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
    /**
     * `true` when the rendered `title` + `description` came from the
     * canonical English copy because the user's active locale isn't in
     * the registry's `localizations` map for this entry. Drives the
     * "Description in English" tag on the browse-source detail screen.
     * Always `false` for installed skills (the on-disk localized
     * manifest loader picks the right variant) and for English users.
     */
    val englishFallback: Boolean,
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
            activeLocale: String,
        ): SkillDetailView {
            // Browse-source: prefer entry.localizations[activeLocale] over
            // the canonical English fields. Installed-source: the
            // `manifest` argument has already been read by the on-device
            // localized loader (TODO Phase 11+: switch the FFI to return
            // for_locale results) so it's already correct.
            val browseLocalized = if (browse != null && activeLocale != "en") {
                browse.localizations[activeLocale]
                    ?: browse.localizations.entries
                        .firstOrNull { it.key.equals(activeLocale, ignoreCase = true) }?.value
            } else null
            val title = manifest?.name?.takeIf { it.isNotBlank() }
                ?: browseLocalized?.name?.takeIf { it.isNotBlank() }
                ?: browse?.name?.takeIf { it.isNotBlank() }
                ?: fallbackId
            val version = manifest?.version ?: browse?.version ?: ""
            val description = manifest?.description?.takeIf { it.isNotBlank() }
                ?: browseLocalized?.description?.takeIf { it.isNotBlank() }
                ?: browse?.description.orEmpty()
            // Browse-source view falls back to English iff:
            //   - we're rendering from the browse entry (no installed manifest), AND
            //   - the active locale isn't English, AND
            //   - no per-locale entry exists for this skill.
            val englishFallback = manifest == null
                && activeLocale != "en"
                && browseLocalized == null
                && browse?.description?.isNotBlank() == true
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
                englishFallback = englishFallback,
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

/**
 * Map an ISO 639-1 code to its self-name for the locale-mismatch
 * dialog (e.g. `"it"` → `"Italian"`). Self-name rather than
 * translated name keeps the dialog readable when the user is
 * thinking in Italian and the chrome is still English.
 */
private fun displayLanguageName(code: String): String = when (code.lowercase()) {
    "en" -> "English"
    "it" -> "Italian"
    else -> code.uppercase()
}
