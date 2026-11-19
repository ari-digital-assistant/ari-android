package dev.heyari.ari.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R
import dev.heyari.ari.llm.LlmModel
import dev.heyari.ari.llm.MemoryFit
import dev.heyari.ari.llm.PhoneMemory
import dev.heyari.ari.ui.formatBytes
import dev.heyari.ari.ui.theme.LocalAriSemanticColors

private const val RATING_STEPS = 5

private val SizeIcon = Icons.Outlined.Download
private val MemoryIcon = Icons.Outlined.Memory
private val SpeedIcon = Icons.Outlined.Speed
private val AccuracyIcon = Icons.Outlined.TaskAlt

/**
 * What an on-device model takes and gives, as both model pickers show it: its
 * download, the memory it uses while answering, and its speed and accuracy
 * against the other tiers. The memory row warns when that's tight on this
 * phone and says what phone it needs when it's too big (ari-android#42).
 * [fit] is null when Android won't report the phone's memory.
 */
@Composable
fun LlmModelFacts(model: LlmModel, fit: MemoryFit?) {
    val plain = MaterialTheme.colorScheme.onSurfaceVariant
    val memory = formatBytes(model.runtimeBytes)
    val (memoryText, memoryColor) = when (fit) {
        MemoryFit.TooBig ->
            stringResource(R.string.llm_memory_too_big, memory, PhoneMemory.minimumGb(model)) to
                MaterialTheme.colorScheme.error
        MemoryFit.Tight ->
            stringResource(R.string.llm_memory_tight, memory) to LocalAriSemanticColors.current.warning
        else -> memory to plain
    }
    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FactText(
            SizeIcon,
            stringResource(R.string.llm_download_size),
            stringResource(R.string.llm_size_installed, formatBytes(model.totalBytes), formatBytes(model.installedBytes)),
            plain,
        )
        FactText(MemoryIcon, stringResource(R.string.llm_memory_use), memoryText, memoryColor)
        FactRating(SpeedIcon, stringResource(R.string.llm_speed, model.speed, RATING_STEPS), model.speed)
        FactRating(AccuracyIcon, stringResource(R.string.llm_accuracy, model.accuracy, RATING_STEPS), model.accuracy)
    }
}

/**
 * The key to [LlmModelFacts]' icons, shown once above the models. Screen
 * readers skip it: each row already says what it is.
 */
@Composable
fun LlmModelFactsLegend(modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LegendItem(SizeIcon, stringResource(R.string.llm_legend_size))
        LegendItem(MemoryIcon, stringResource(R.string.llm_legend_memory))
        LegendItem(SpeedIcon, stringResource(R.string.llm_legend_speed))
        LegendItem(AccuracyIcon, stringResource(R.string.llm_legend_accuracy))
    }
}

@Composable
private fun LegendItem(icon: ImageVector, label: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun FactText(icon: ImageVector, label: String, text: String, color: Color) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun FactRating(icon: ImageVector, label: String, value: Int) {
    val filled = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(RATING_STEPS) { step ->
                Box(
                    Modifier
                        .size(width = 16.dp, height = 6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (step < value) filled else empty),
                )
            }
        }
    }
}
