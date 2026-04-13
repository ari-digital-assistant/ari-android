package dev.heyari.ari.ui.settings.pages

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.TtsVoiceOption
import dev.heyari.ari.ui.settings.components.SettingsScaffold
import java.util.Locale

@Composable
fun TtsSettingsPage(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_category_tts),
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            TtsVoicesSection(
                voices = state.ttsVoices,
                activeTtsVoice = state.activeTtsVoice,
                onSelect = viewModel::selectTtsVoice,
                onPreview = viewModel::previewTtsVoice,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsVoicesSection(
    voices: List<TtsVoiceOption>,
    activeTtsVoice: String?,
    onSelect: (String?) -> Unit,
    onPreview: (String) -> Unit,
) {
    val locales = remember(voices) {
        voices.map { it.locale }.distinct().sorted()
    }

    val systemLocaleDisplay = Locale.getDefault().displayName
    var selectedLocale by remember(locales) {
        mutableStateOf(
            if (locales.contains(systemLocaleDisplay)) systemLocaleDisplay
            else locales.firstOrNull() ?: ""
        )
    }

    val filteredVoices = remember(voices, selectedLocale) {
        voices.filter { it.locale == selectedLocale }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Choose which voice Ari uses to speak. Pick a language, then select a voice. Tap the speaker icon to preview.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (activeTtsVoice == null) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = activeTtsVoice == null,
                    onClick = { onSelect(null) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "System default",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Uses your device\u2019s default voice",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (locales.isEmpty()) {
            Text(
                text = "No voices available yet. They should appear shortly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        Spacer(Modifier.height(8.dp))

        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selectedLocale,
                onValueChange = {},
                readOnly = true,
                label = { Text("Language") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                locales.forEach { locale ->
                    DropdownMenuItem(
                        text = { Text(locale) },
                        onClick = {
                            selectedLocale = locale
                            expanded = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        filteredVoices.forEach { option ->
            VoiceCard(
                option = option,
                onSelect = onSelect,
                onPreview = onPreview,
            )
        }
    }
}

@Composable
private fun VoiceCard(
    option: TtsVoiceOption,
    onSelect: (String?) -> Unit,
    onPreview: (String) -> Unit,
) {
    val hasBoth = option.localName != null && option.networkName != null
    // Which variant to select/preview when the user taps this card
    val activeName = if (option.activeIsNetwork) option.networkName else option.localName
    // For preview, use whichever variant is currently toggled
    val previewName = activeName ?: option.localName ?: option.networkName ?: return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (option.active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = option.active,
                onClick = { onSelect(activeName) },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.displayLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (hasBoth) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        FilterChip(
                            selected = !option.activeIsNetwork,
                            onClick = { onSelect(option.localName) },
                            label = { Text("On device", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(28.dp),
                        )
                        FilterChip(
                            selected = option.activeIsNetwork,
                            onClick = { onSelect(option.networkName) },
                            label = { Text("Cloud", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(28.dp),
                        )
                    }
                } else if (option.networkName != null && option.localName == null) {
                    Text(
                        text = "Cloud only",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { onPreview(previewName) }) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = "Preview voice",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
