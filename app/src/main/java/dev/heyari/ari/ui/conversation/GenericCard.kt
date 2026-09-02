package dev.heyari.ari.ui.conversation

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.heyari.ari.assets.AssetResolver
import dev.heyari.ari.data.card.Card as CardModel
import androidx.compose.ui.res.stringResource
import dev.heyari.ari.R
import dev.heyari.ari.data.card.CardAction
import dev.heyari.ari.notifications.AlertRegistry
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
 * Action swap: if the card declares an `on_complete.alert` and that
 * alert is currently sounding (per [AlertRegistry]), the rendered
 * actions are replaced with a single **Stop** button that targets the
 * reserved `stop_alert` id. The Cancel button the skill normally emits
 * is only relevant during the countdown; once the alert is ringing the
 * user wants one thing — to silence it — and the card is their
 * closest control.
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
        FinishedChip(label = stringResource(R.string.card_status_done), modifier = modifier)
        return
    }

    val active by AlertRegistry.active.collectAsState()
    val isRinging = card.onComplete?.alert?.id?.let { it in active } == true

    if (card.stat != null) {
        StatCard(card, card.stat, onAction, assetResolver, modifier)
        return
    }
    if (card.list != null) {
        ListVariantCard(card, card.list, onAction, assetResolver, modifier)
        return
    }

    val container = accentContainer(card.accent)
    val onContainer = accentOnContainer(card.accent)
    val accentBar = accentBar(card.accent)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 340.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Header(card = card, onContainer = onContainer, assetResolver = assetResolver)
            Spacer(Modifier.height(12.dp))
            when {
                card.countdownToTsMs != null -> CountdownBody(
                    card = card,
                    onContainer = onContainer,
                    accentBar = accentBar,
                    isRinging = isRinging,
                )
                card.progress != null -> ProgressBody(card, onContainer, accentBar)
                else -> PlainBody(card, onContainer)
            }
            val renderedActions =
                actionsForState(card, isRinging, stringResource(R.string.card_action_stop))
            if (renderedActions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ActionsRow(renderedActions, onAction, onContainer)
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
        val icon = rememberAsset(card.skillId, card.icon, assetResolver)
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.size(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = onContainer,
            )
            if (card.subtitle != null) {
                Text(
                    text = card.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun CountdownBody(
    card: CardModel,
    onContainer: Color,
    accentBar: Color,
    isRinging: Boolean,
) {
    val end = card.countdownToTsMs ?: return
    val started = card.startedAtTsMs ?: System.currentTimeMillis()
    val durationMs = (end - started).coerceAtLeast(1L)
    val remaining = remainingTimeMs(endTsMs = end).value
    val progress = 1f - (remaining.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    val expired = remaining <= 0L

    // <10s remaining: urgency tint. <3s (or ringing): full error tint on
    // the digits. Gives the card a visible "about to fire" signal.
    val urgent = !expired && remaining < 10_000L
    val criticalTint = expired || remaining < 3_000L
    val barColor = if (urgent || isRinging) MaterialTheme.colorScheme.error else accentBar
    val digitsColor = if (criticalTint || isRinging) MaterialTheme.colorScheme.error else onContainer

    val label = when {
        isRinging -> stringResource(R.string.card_status_ringing)
        expired -> stringResource(R.string.card_status_done)
        else -> stringResource(R.string.card_status_remaining)
    }
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = (if (isRinging) MaterialTheme.colorScheme.error else onContainer).copy(alpha = 0.75f),
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(2.dp))
    CountdownDigits(
        text = formatRemaining(remaining),
        color = digitsColor,
        blink = !expired && !isRinging,
    )
    Spacer(Modifier.height(12.dp))
    LinearProgressIndicator(
        progress = { progress },
        color = barColor,
        trackColor = onContainer.copy(alpha = 0.15f),
        strokeCap = StrokeCap.Round,
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
    )
}

@Composable
private fun CountdownDigits(text: String, color: Color, blink: Boolean) {
    // Split on the last ':' so only the seconds-separator blinks, and only
    // when the timer is actually counting down (not at 0 / not ringing).
    val colonIx = text.lastIndexOf(':')
    val alpha = if (blink) {
        val transition = rememberInfiniteTransition(label = "colon")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 500, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "colonAlpha",
        ).value
    } else {
        1f
    }
    val style = MaterialTheme.typography.displayLarge.copy(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 56.sp,
    )
    if (colonIx < 0) {
        Text(
            text = text,
            style = style,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text.substring(0, colonIx), style = style, color = color)
        Text(text = ":", style = style, color = color.copy(alpha = alpha))
        Text(text = text.substring(colonIx + 1), style = style, color = color)
    }
}

@Composable
private fun ProgressBody(card: CardModel, onContainer: Color, accentBar: Color) {
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
        color = accentBar,
        trackColor = onContainer.copy(alpha = 0.15f),
        strokeCap = StrokeCap.Round,
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
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

/**
 * Returns the buttons to show for a card in its current state. During a
 * ringing alert the skill's declared actions are replaced with a single
 * Stop button — the user wants to silence the alert, nothing else.
 */
private fun actionsForState(
    card: CardModel,
    isRinging: Boolean,
    stopLabel: String,
): List<CardAction> {
    if (!isRinging) return card.actions
    return listOf(
        CardAction(
            id = "stop_alert",
            label = stopLabel,
            utterance = null,
            speak = null,
            style = CardAction.Style.DESTRUCTIVE,
        ),
    )
}

@Composable
private fun ActionsRow(actions: List<CardAction>, onAction: (CardAction) -> Unit, tint: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        for ((index, action) in actions.withIndex()) {
            if (index > 0) Spacer(Modifier.size(8.dp))
            ActionButton(action = action, onClick = { onAction(action) }, defaultTint = tint)
        }
    }
}

@Composable
private fun ActionButton(action: CardAction, onClick: () -> Unit, defaultTint: Color) {
    val error = MaterialTheme.colorScheme.error
    val (contentColor, borderBrushColor) = when (action.style) {
        CardAction.Style.DESTRUCTIVE -> error to error
        CardAction.Style.PRIMARY -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primary
        CardAction.Style.DEFAULT -> defaultTint to defaultTint.copy(alpha = 0.4f)
    }
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderBrushColor),
    ) { Text(action.label) }
}

@Composable
private fun FinishedChip(label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 340.dp),
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

/**
 * Bar colour for the progress indicator. Separate from `accentOnContainer`
 * because the bar wants a saturated fill, not a text tint. Default/Success
 * map to `primary`; Warning/Critical to `error`.
 */
@Composable
private fun accentBar(accent: CardModel.Accent): Color = when (accent) {
    CardModel.Accent.WARNING, CardModel.Accent.CRITICAL -> MaterialTheme.colorScheme.error
    CardModel.Accent.SUCCESS -> MaterialTheme.colorScheme.primary
    CardModel.Accent.DEFAULT -> MaterialTheme.colorScheme.primary
}

/** Decode a skill asset to an ImageBitmap once, cached across recompositions. */
@Composable
private fun rememberAsset(
    skillId: String,
    reference: String?,
    assetResolver: AssetResolver?,
): androidx.compose.ui.graphics.ImageBitmap? {
    if (reference == null || assetResolver == null) return null
    return remember(skillId, reference, assetResolver) {
        assetResolver.resolve(skillId, reference)?.let { file ->
            runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
        }
    }
}

@Composable
private fun StatCard(
    card: CardModel,
    stat: dev.heyari.ari.data.card.Stat,
    onAction: (CardAction) -> Unit,
    assetResolver: AssetResolver?,
    modifier: Modifier,
) {
    val container = accentContainer(card.accent)
    val onContainer = accentOnContainer(card.accent)
    val bg = rememberAsset(card.skillId, stat.background, assetResolver)
    Card(
        modifier = modifier.fillMaxWidth().widthIn(max = 360.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        // With a background, make the card square so the full (square)
        // illustration shows without vertical cropping; content is laid over
        // it, header/temp up top and metrics pinned to the bottom. Without a
        // background, fall back to a content-sized card.
        val hasBg = bg != null
        val boxModifier = if (hasBg) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.fillMaxWidth()
        Box(modifier = boxModifier) {
            if (bg != null) {
                Image(
                    bitmap = bg,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    modifier = Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.25f)),
                        ),
                    ),
                )
            }
            Column(
                modifier = (if (hasBg) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).padding(16.dp),
            ) {
                Header(card = card, onContainer = onContainer, assetResolver = assetResolver)
                Spacer(Modifier.height(8.dp))
                Text(stat.headline, style = MaterialTheme.typography.displayMedium, color = onContainer)
                if (stat.caption != null) {
                    Text(stat.caption, style = MaterialTheme.typography.titleMedium,
                        color = onContainer.copy(alpha = 0.85f))
                }
                if (stat.pill != null) {
                    Spacer(Modifier.height(12.dp))
                    FrostedChip(stat.pill, card.skillId, assetResolver, onContainer)
                }
                // Push the metrics/footer to the bottom of the square card.
                Spacer(Modifier.weight(1f, fill = hasBg))
                if (stat.metrics.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        stat.metrics.forEach { m -> IconLabel(m, card.skillId, assetResolver, onContainer) }
                    }
                }
                if (stat.footer != null) {
                    Spacer(Modifier.height(12.dp))
                    IconLabel(stat.footer, card.skillId, assetResolver, onContainer.copy(alpha = 0.6f), small = true)
                }
                if (card.actions.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    ActionsRow(card.actions, onAction, onContainer)
                }
            }
        }
    }
}

@Composable
private fun FrostedChip(item: dev.heyari.ari.data.card.IconText, skillId: String,
    assetResolver: AssetResolver?, onContainer: Color) {
    Surface(color = Color.White.copy(alpha = 0.55f), shape = RoundedCornerShape(50)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AssetIcon(item.icon, skillId, assetResolver, 18.dp)
            Text(item.text, style = MaterialTheme.typography.bodyMedium, color = onContainer)
        }
    }
}

@Composable
private fun IconLabel(item: dev.heyari.ari.data.card.IconText, skillId: String,
    assetResolver: AssetResolver?, color: Color, small: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AssetIcon(item.icon, skillId, assetResolver, if (small) 14.dp else 20.dp)
        Text(item.text,
            style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
            color = color)
    }
}

@Composable
private fun AssetIcon(reference: String?, skillId: String, assetResolver: AssetResolver?, size: Dp) {
    val bmp = rememberAsset(skillId, reference, assetResolver)
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(size))
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
private fun ListVariantCard(
    card: CardModel,
    list: dev.heyari.ari.data.card.ListCard,
    onAction: (CardAction) -> Unit,
    assetResolver: AssetResolver?,
    modifier: Modifier,
) {
    val container = accentContainer(card.accent)
    val onContainer = accentOnContainer(card.accent)
    Card(
        modifier = modifier.fillMaxWidth().widthIn(max = 360.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Header(card = card, onContainer = onContainer, assetResolver = assetResolver)
        if (list.summary != null) {
            Spacer(Modifier.height(12.dp))
            FrostedChip(list.summary, card.skillId, assetResolver, onContainer)
        }
        Spacer(Modifier.height(8.dp))
        list.rows.forEach { row ->
            Surface(
                color = onContainer.copy(alpha = 0.05f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.leading, style = MaterialTheme.typography.titleSmall, color = onContainer,
                        modifier = Modifier.width(40.dp))
                    AssetIcon(row.icon, card.skillId, assetResolver, 34.dp)
                    Text(
                        row.text ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Fixed-width, right-aligned temps so they line up across
                    // every row regardless of the (reserved) badge slot.
                    Text(
                        row.trailing ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(84.dp),
                    )
                    Box(modifier = Modifier.width(58.dp), contentAlignment = Alignment.CenterEnd) {
                        if (row.badge != null) {
                            IconLabel(row.badge, card.skillId, assetResolver,
                                onContainer.copy(alpha = 0.7f), small = true)
                        }
                    }
                }
            }
        }
        if (list.footer != null) {
            Spacer(Modifier.height(8.dp))
            IconLabel(list.footer, card.skillId, assetResolver, onContainer.copy(alpha = 0.6f), small = true)
        }
        if (card.actions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ActionsRow(card.actions, onAction, onContainer)
        }
      }
    }
}
