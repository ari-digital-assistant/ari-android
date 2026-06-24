package dev.heyari.ari.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.heyari.ari.model.ConversationState
import dev.heyari.ari.ui.components.AriTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import dev.heyari.ari.R
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.heyari.ari.stt.SttState

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
    val bannerState by updateBannerViewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current


    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            viewModel.setWakeWordEnabled(true)
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

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    // Chat-app behaviour: when the IME opens (user tapped the text field),
    // snap the conversation to the bottom so the latest message + the reply
    // stay visible above the keyboard rather than being occluded.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }


    Scaffold(
        topBar = {
            AriTopBar(
                onOpenMenu = onOpenMenu,
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 4.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (state.isListening) R.string.conversation_listening_status_on
                                else R.string.conversation_listening_status_off
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Switch(
                            checked = state.isListening,
                            onCheckedChange = { wantsOn ->
                                if (!wantsOn) {
                                    viewModel.setWakeWordEnabled(false)
                                    return@Switch
                                }
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
                                    viewModel.setWakeWordEnabled(true)
                                } else {
                                    val needed = mutableListOf<String>()
                                    if (!hasAudio) needed.add(Manifest.permission.RECORD_AUDIO)
                                    if (!hasNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        needed.add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    permissionLauncher.launch(needed.toTypedArray())
                                }
                            },
                            thumbContent = {
                                Icon(
                                    imageVector = if (state.isListening) Icons.Default.Mic else Icons.Default.MicOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        cardRepository = viewModel.cardRepository,
                        assetResolver = viewModel.assetResolver,
                        onCardAction = viewModel::onCardAction,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.inputText,
                    onValueChange = viewModel::onInputChanged,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.conversation_input_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { viewModel.onTextSubmitted(state.inputText) }
                    ),
                )
                IconButton(onClick = { viewModel.onTextSubmitted(state.inputText) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send"
                    )
                }
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

