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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
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
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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

    if (state.needsFsnPermission) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissFsnPrompt() },
            title = { Text("Full-screen notifications") },
            text = { Text("For hands-free wake word activation, Ari needs permission to show full-screen notifications. Without this, you'll get a banner notification instead.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissFsnPrompt()
                    viewModel.openFsnSettings()
                }) {
                    Text("Open settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissFsnPrompt() }) {
                    Text("Later")
                }
            }
        )
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
                            text = if (state.isListening) "Listening" else "Not listening",
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

            DownloadProgressCard(state)

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
                        timerRepository = viewModel.timerRepository,
                        onCancelTimer = viewModel::onTimerCancelRequested,
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
                    placeholder = { Text("Ask Ari something...") },
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
                    text = "Setup needed",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Voice features need a microphone permission and a downloaded speech model. You can still type queries below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenMenu) {
                    Text("Open menu")
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
                text = "Downloading in the background",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))

            if (sttDownloading) {
                DownloadRow(
                    label = "Voice recognition model",
                    state = state.sttDownload,
                )
            }
            if (llmDownloading) {
                DownloadRow(
                    label = "Assistant model",
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

