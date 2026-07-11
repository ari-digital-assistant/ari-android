package dev.heyari.ari.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.heyari.ari.model.ConversationState
import dev.heyari.ari.ui.components.AriTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.heyari.ari.R
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun ConversationScreen(
    onOpenMenu: () -> Unit = {},
    onOpenAutoUpdate: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    /**
     * Opens the Skills browser pre-filtered to assistant-type skills.
     * Used by the cloud-assistant empty-state hint card. The unfiltered
     * `onOpenSkills` is kept separate so other entry points (top-bar,
     * onboarding nudge) don't pick up the filter as a side effect.
     */
    onOpenAssistantSkills: () -> Unit = {},
    viewModel: ConversationViewModel = hiltViewModel(),
    updateBannerViewModel: UpdateBannerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val voicePhase by viewModel.voicePhase.collectAsStateWithLifecycle()
    val bannerState by updateBannerViewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current


    // What to run once RECORD_AUDIO (+ POST_NOTIFICATIONS on 33+) come back
    // granted. Set right before launching the request and consumed in the
    // launcher callback, so a SINGLE launcher serves both the wake switch and
    // the composer's mic button (see [withVoicePermissions]).
    val onVoicePermissionsGranted = remember { mutableStateOf<() -> Unit>({}) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) onVoicePermissionsGranted.value()
        onVoicePermissionsGranted.value = {}
    }

    // Runs [onGranted] immediately if the voice permissions are already held,
    // otherwise stashes it and launches the request; the launcher callback runs
    // it once RECORD_AUDIO is granted. Shared by the wake switch and the mic tap
    // so there's exactly one launcher and one permission policy.
    fun withVoicePermissions(onGranted: () -> Unit) {
        val hasAudio = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (hasAudio && hasNotifications) {
            onGranted()
        } else {
            onVoicePermissionsGranted.value = onGranted
            val needed = mutableListOf<String>()
            if (!hasAudio) needed.add(Manifest.permission.RECORD_AUDIO)
            if (!hasNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // Refresh on every entry into RESUMED (handles activity resume) AND on every
    // composition of this destination (handles NavHost back-navigation, where the
    // activity stays resumed but the destination re-enters the tree).
    LaunchedEffect(Unit) {
        viewModel.syncServiceState()
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.syncServiceState()
        }
    }

    LaunchedEffect(messages.size, state.isThinking) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Chat-app behaviour: when the IME opens (user tapped the text field),
    // snap the conversation to the bottom so the latest message + the reply
    // stay visible above the keyboard rather than being occluded.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }


    Scaffold(
        topBar = {
            AriTopBar(
                onOpenMenu = onOpenMenu,
                actions = {
                    WakeSwitch(
                        armed = state.isListening,
                        onToggle = { wantsOn ->
                            if (!wantsOn) {
                                viewModel.setWakeWordEnabled(false)
                            } else {
                                withVoicePermissions { viewModel.setWakeWordEnabled(true) }
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Only the top (app-bar) inset pads the column; the bottom
                // navigation-bar inset is handled by the composer itself so the
                // presence aura can paint edge-to-edge behind the gesture bar.
                .padding(top = padding.calculateTopPadding())
                .imePadding()
        ) {
            if (state.needsSetup) {
                OnboardingCard(onOpenMenu = onOpenMenu)
            }

            if (state.needsCloudAssistantSetup) {
                CloudAssistantSetupCard(onOpenSkills = onOpenAssistantSkills)
            }

            UpdateBanners(
                state = bannerState,
                onApplyAllModels = updateBannerViewModel::applyAllModels,
                onApplyAllSkills = updateBannerViewModel::applyAllSkills,
                onOpenModelDetails = {
                    updateBannerViewModel.acknowledgeModels()
                    onOpenAutoUpdate()
                },
                onOpenSkillDetails = {
                    updateBannerViewModel.acknowledgeSkills()
                    onOpenSkills()
                },
                onDismissModels = updateBannerViewModel::dismissModelBanner,
                onDismissSkills = updateBannerViewModel::dismissSkillBanner,
                onDismissTerminal = updateBannerViewModel::dismissTerminalMessage,
            )

            // Suppress the generic per-model "Downloading in the background"
            // card while an Update All is in flight — the UpdateBanners
            // progress banner already covers the same ground with better
            // context (X of Y, current item name).
            if (bannerState.applying == null) {
                DownloadProgressCard(state)
            }

            // Empty conversation → adaptive empty state (first-run CTA or
            // greeting + suggestion chips). Once a turn is in flight
            // (isThinking) or any message exists, fall through to the list so
            // the thinking indicator and history render as before.
            if (messages.isEmpty() && !state.isThinking) {
                EmptyState(
                    mode = state.emptyMode,
                    greeting = state.greeting,
                    chips = state.suggestionChips,
                    onChip = { viewModel.onTextSubmitted(it) },
                    onBrowseSkills = onOpenSkills,
                    modifier = Modifier.weight(1f),
                )
            } else {
                val rows = remember(messages) { MessageGrouping.rows(messages) }
                val motion = remember { animationsEnabled(context) }
                // Remember which bubbles have already played their entrance, so
                // each animates once (on first appearance) and not again when it
                // scrolls back into view.
                val animatedIds = remember { mutableSetOf<String>() }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    // Grouping now controls intra/inter-group spacing via corner
                    // radii, so the column spacing drops from 8.dp to 2.dp.
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(rows, key = { it.message.id }) { row ->
                        val id = row.message.id
                        // Captured at first composition: has this bubble already
                        // appeared (and thus already animated) before now?
                        val firstAppearance = remember(id) { id !in animatedIds }
                        LaunchedEffect(id) { animatedIds.add(id) }
                        if (motion && firstAppearance) {
                            // Start hidden, then flip to visible so the enter
                            // transition actually runs. AnimatedVisibility(visible =
                            // true) would snap in with no animation.
                            val enterState = remember(id) {
                                MutableTransitionState(false).apply { targetState = true }
                            }
                            AnimatedVisibility(
                                visibleState = enterState,
                                enter = fadeIn(animationSpec = tween(180)) +
                                    slideInVertically(
                                        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
                                        initialOffsetY = { it / 3 },
                                    ),
                                modifier = Modifier.animateItem(),
                            ) {
                                MessageBubble(
                                    row = row,
                                    cardRepository = viewModel.cardRepository,
                                    assetResolver = viewModel.assetResolver,
                                    onCardAction = viewModel::onCardAction,
                                )
                            }
                        } else {
                            MessageBubble(
                                row = row,
                                modifier = Modifier.animateItem(),
                                cardRepository = viewModel.cardRepository,
                                assetResolver = viewModel.assetResolver,
                                onCardAction = viewModel::onCardAction,
                            )
                        }
                    }
                    if (state.isThinking) {
                        item(key = "thinking-indicator") {
                            ThinkingIndicator(Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }

            // Presence aura layered behind the composer. Its rhythm follows the
            // combined ambient state: voice phase (Listening/Thinking/Speaking)
            // takes precedence, with the typed-input "still working" flag
            // folding into Thinking. Reduce-motion is handled inside AmbientField.
            val ambient = deriveAmbientState(voicePhase, state.isThinking)
            Box(modifier = Modifier.fillMaxWidth()) {
                AmbientField(
                    state = ambient,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                AriComposer(
                    value = state.inputText,
                    onValueChange = viewModel::onInputChanged,
                    onSend = { viewModel.onTextSubmitted(state.inputText) },
                    onMicTap = { withVoicePermissions { viewModel.startVoiceTurn() } },
                    ambientState = ambient,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }
        }
    }
}

@Composable
private fun OnboardingCard(onOpenMenu: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.conversation_setup_needed_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.conversation_voice_disabled_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenMenu) {
                    Text(stringResource(R.string.conversation_open_menu))
                }
            }
        }
    }
}

/**
 * Empty-state hint for users who picked the Cloud assistant option
 * during onboarding but haven't installed and activated a cloud
 * assistant skill yet. Without this nudge they get a "Scusa, non ho
 * capito"-class fallback for any non-skill-matched query and have no
 * signal that the install step is still pending.
 *
 * Cleared automatically (via `selectAssistant`) once the user picks
 * any assistant — the underlying flag in
 * `SettingsRepository.pendingCloudAssistantSetup` flips to `false`.
 */
@Composable
private fun CloudAssistantSetupCard(onOpenSkills: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.conversation_cloud_setup_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.conversation_cloud_setup_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenSkills) {
                    Text(stringResource(R.string.conversation_cloud_setup_button))
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressCard(state: ConversationState) {
    val sttDownloading = state.sttDownload is dev.heyari.ari.stt.ModelDownloadState.Downloading
    val llmDownloading = state.llmDownload is dev.heyari.ari.llm.LlmDownloadState.Downloading

    if (!sttDownloading && !llmDownloading) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.conversation_downloading_header),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))

            if (sttDownloading) {
                DownloadRow(
                    label = stringResource(R.string.conversation_downloading_stt_model),
                    state = state.sttDownload,
                )
            }
            if (llmDownloading) {
                DownloadRow(
                    label = stringResource(R.string.conversation_downloading_llm_model),
                    state = state.llmDownload,
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(label: String, state: Any) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        when (state) {
            is dev.heyari.ari.stt.ModelDownloadState.Downloading -> {
                Text(label, style = MaterialTheme.typography.bodySmall)
                if (state.totalBytes > 0) {
                    val progress = state.bytesSoFar.toFloat() / state.totalBytes.toFloat()
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                } else {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
            }
            is dev.heyari.ari.llm.LlmDownloadState.Downloading -> {
                Text(label, style = MaterialTheme.typography.bodySmall)
                if (state.totalBytes > 0) {
                    val progress = state.bytesSoFar.toFloat() / state.totalBytes.toFloat()
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                } else {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

