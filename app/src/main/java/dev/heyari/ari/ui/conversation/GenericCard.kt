package dev.heyari.ari.ui.conversation

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.heyari.ari.assets.AssetResolver
import dev.heyari.ari.data.card.Card as CardModel
import dev.heyari.ari.data.card.CardAction
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/**
 * Field-driven renderer for any presentation card.
 *
 * - Has `countdownToTsMs` → live countdown layout (1Hz coroutine tick).
 * - Has `progress` → static progress bar + body.
 * - Otherwise → plain title/subtitle/body card.
 *
 * No "kind" enum: a skill describes its card by which fields it sets.
 * Adding a new visual variant is "renderer reads a new optional field"
 * not "renderer adds a new branch in a hard-coded enum".
 *
 * `card == null` collapses into a muted "Done" chip — happens after the
 * repo drops the entry on expiry, so the chat bubble keeps making sense.
 */
@Composable
fun GenericCard(
    card: CardModel?,
    onAction: (CardAction) -> Unit,
    assetResolver: AssetResolver?,
    modifier: Modifier = Modifier,
) {
    if (card == null) {
        FinishedChip(label = "Done", modifier = modifier)
        return
    }

    val container = accentContainer(card.accent)
    val onContainer = accentOnContainer(card.accent)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 300.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Header(card = card, onContainer = onContainer, assetResolver = assetResolver)
            when {
                card.countdownToTsMs != null -> CountdownBody(card, onContainer)
                card.progress != null -> ProgressBody(card, onContainer)
                else -> PlainBody(card, onContainer)
            }
            if (card.actions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                ActionsRow(card.actions, onAction, onContainer)
            }
        }
    }
}

@Composable
private fun Header(card: CardModel, onContainer: Color, assetResolver: AssetResolver?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (card.icon != null && assetResolver != null) {
            val painter = remember(card.icon, card.skillId, assetResolver) {
                assetResolver.resolve(card.skillId, card.icon)?.let { file ->
                    runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                }
            }
            if (painter != null) {
                Image(
                    bitmap = painter.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.labelLarge,
                color = onContainer,
            )
            if (card.subtitle != null) {
                Text(
                    text = card.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer,
                )
            }
        }
    }
}

@Composable
private fun CountdownBody(card: CardModel, onContainer: Color) {
    val end = card.countdownToTsMs ?: return
    val started = card.startedAtTsMs ?: System.currentTimeMillis()
    val durationMs = (end - started).coerceAtLeast(1L)
    val remaining = remainingTimeMs(endTsMs = end).value
    val progress = 1f - (remaining.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    Text(
        text = formatRemaining(remaining),
        style = MaterialTheme.typography.displaySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        ),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = onContainer,
    )
    Spacer(Modifier.height(4.dp))
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ProgressBody(card: CardModel, onContainer: Color) {
    if (card.body != null) {
        Text(
            text = card.body,
            style = MaterialTheme.typography.bodyMedium,
            color = onContainer,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { card.progress!!.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PlainBody(card: CardModel, onContainer: Color) {
    if (card.body != null) {
        Text(
            text = card.body,
            style = MaterialTheme.typography.bodyMedium,
            color = onContainer,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ActionsRow(actions: List<CardAction>, onAction: (CardAction) -> Unit, tint: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        for (action in actions) {
            TextButton(
                onClick = { onAction(action) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = tint),
            ) { Text(action.label) }
        }
    }
}

@Composable
private fun FinishedChip(label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 300.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun remainingTimeMs(endTsMs: Long): State<Long> =
    produceState(initialValue = max(0L, endTsMs - System.currentTimeMillis()), endTsMs) {
        while (true) {
            val now = System.currentTimeMillis()
            value = max(0L, endTsMs - now)
            if (value <= 0) break
            // Tick at the next whole second boundary so MM:SS doesn't jitter.
            delay(min(1000L, 1000L - (now % 1000L).coerceAtLeast(1L)))
        }
    }

private fun formatRemaining(ms: Long): String {
    val totalSecs = ms / 1000
    val hours = totalSecs / 3600
    val minutes = (totalSecs % 3600) / 60
    val seconds = totalSecs % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun accentContainer(accent: CardModel.Accent): Color = when (accent) {
    CardModel.Accent.WARNING -> MaterialTheme.colorScheme.errorContainer
    CardModel.Accent.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
    CardModel.Accent.CRITICAL -> MaterialTheme.colorScheme.errorContainer
    CardModel.Accent.DEFAULT -> MaterialTheme.colorScheme.tertiaryContainer
}

@Composable
private fun accentOnContainer(accent: CardModel.Accent): Color = when (accent) {
    CardModel.Accent.WARNING -> MaterialTheme.colorScheme.onErrorContainer
    CardModel.Accent.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
    CardModel.Accent.CRITICAL -> MaterialTheme.colorScheme.onErrorContainer
    CardModel.Accent.DEFAULT -> MaterialTheme.colorScheme.onTertiaryContainer
}
