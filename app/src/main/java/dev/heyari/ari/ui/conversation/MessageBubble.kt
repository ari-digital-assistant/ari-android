package dev.heyari.ari.ui.conversation

import android.content.ClipData
import android.content.ClipboardManager
import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.material3.RichText
import dev.heyari.ari.R
import dev.heyari.ari.assets.AssetResolver
import dev.heyari.ari.data.card.CardAction
import dev.heyari.ari.data.card.CardStateRepository
import dev.heyari.ari.model.Attachment
import dev.heyari.ari.model.Message
import java.util.Date

private val BIG = 18.dp
private val TAIL = 4.dp
private val ACCENT = 3.dp

@Composable
fun MessageBubble(
    row: MessageRow,
    modifier: Modifier = Modifier,
    cardRepository: CardStateRepository? = null,
    assetResolver: AssetResolver? = null,
    onCardAction: (cardId: String, action: CardAction) -> Unit = { _, _ -> },
    onReport: (Message) -> Unit = {},
    onReadAloud: (Message) -> Unit = {},
    onOpenSkill: (skillId: String) -> Unit = {},
) {
    val message = row.message
    val isUser = message.isFromUser
    val glyph = modalityGlyph(message)   // null for Ari rows; drives the trailing gutter

    Column(modifier = modifier.fillMaxWidth()) {
        if (row.showTimestamp) {
            Text(
                text = formatTimestamp(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
        }

        if (message.isNote) {
            ConversationNote(message.text)
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            if (!isUser) {
                // Avatar gutter: real avatar only on the group's last (newest)
                // Ari row; a spacer keeps earlier rows aligned.
                if (row.isLastInGroup) {
                    AriAvatar(modifier = Modifier.padding(end = 8.dp, top = 2.dp))
                } else {
                    Spacer(Modifier.width(32.dp))
                }
            }

            val shape = if (isUser) {
                RoundedCornerShape(
                    topStart = BIG, topEnd = BIG, bottomStart = BIG,
                    bottomEnd = if (row.isLastInGroup) TAIL else BIG,
                )
            } else {
                RoundedCornerShape(
                    topStart = BIG, topEnd = BIG, bottomEnd = BIG,
                    bottomStart = if (row.isLastInGroup) TAIL else BIG,
                )
            }

            // Long-press menu. Copy is on every bubble because people expect it
            // on a chat message, and Copy as Markdown on an answer the model
            // formatted; Read aloud and Report are only on Ari's, because
            // hearing or reporting your own words is meaningless.
            var menuOpen by remember(message.id) { mutableStateOf(false) }
            val context = LocalContext.current
            Box {
            Surface(
                shape = shape,
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = if (isUser) 0.dp else 2.dp,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { menuOpen = true },
                    ),
            ) {
                // Hairline accent edge on Ari turns: the bubble's shape less the
                // same shape moved ACCENT inwards, so it follows the corners
                // round. Drawn behind the text rather than laid out beside it,
                // as a bar would need an intrinsic height to match the text's,
                // which a rendered table can't give.
                val accent = MaterialTheme.colorScheme.primary
                val body = if (isUser) Modifier else Modifier
                    .drawWithCache {
                        val bubble = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
                        val inwards = if (layoutDirection == LayoutDirection.Ltr) ACCENT.toPx() else -ACCENT.toPx()
                        val inner = Path().apply { addPath(bubble, Offset(inwards, 0f)) }
                        val edge = Path.combine(PathOperation.Difference, bubble, inner)
                        onDrawBehind { drawPath(edge, accent) }
                    }
                    .padding(start = ACCENT)
                val markdown = message.markdown
                if (markdown != null) {
                    // An answer the model formatted (ari-android#57). RichText
                    // takes bodyLarge and the Surface's content colour, as the
                    // plain Text below does.
                    RichText(modifier = body.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Markdown(content = markdown)
                    }
                } else {
                    Text(
                        text = message.text,
                        modifier = body.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            // The menu doesn't take focus: a focused popup closes the keyboard,
            // and the chat moving down under it shifted the bubble and the menu
            // mid-press. Unfocused, it doesn't get the back press either, so
            // that's handled here.
            BackHandler(enabled = menuOpen) { menuOpen = false }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.message_menu_copy)) },
                    onClick = {
                        menuOpen = false
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText(null, message.text))
                    },
                )
                message.markdown?.let { markdown ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.message_menu_copy_markdown)) },
                        onClick = {
                            menuOpen = false
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText(null, markdown))
                        },
                    )
                }
                if (!isUser) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.message_menu_read_aloud)) },
                        onClick = {
                            menuOpen = false
                            onReadAloud(message)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.message_menu_report)) },
                        onClick = {
                            menuOpen = false
                            onReport(message)
                        },
                    )
                }
            }
            }

            // Trailing modality gutter — mirror of Ari's leading avatar gutter.
            // Present only on user rows (glyph != null), on every row.
            if (glyph != null) {
                MessageModalityGlyph(
                    glyph = glyph,
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                )
            }
        }

        for (attachment in message.attachments) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            ) {
                if (!isUser) Spacer(Modifier.width(32.dp))
                when (attachment) {
                    is Attachment.Card -> {
                        if (cardRepository != null) {
                            val cardFlow = remember(attachment.cardId, cardRepository) {
                                cardRepository.observe(attachment.cardId)
                            }
                            val card = cardFlow.collectAsState(initial = null).value
                            GenericCard(
                                card = card,
                                onAction = { action -> onCardAction(attachment.cardId, action) },
                                assetResolver = assetResolver,
                            )
                        }
                    }
                    is Attachment.SkillSuggestion -> {
                        OutlinedButton(onClick = { onOpenSkill(attachment.skillId) }) {
                            Text(stringResource(R.string.skill_suggestion_open, attachment.name))
                        }
                    }
                }
            }
        }
    }
}

/**
 * A note about the chat rather than a turn in it: centred, faded, and in a
 * dashed outline so it doesn't read as something Ari said.
 */
@Composable
private fun ConversationNote(text: String) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .alpha(0.7f)
                .drawBehind {
                    drawRoundRect(
                        color = outline,
                        cornerRadius = CornerRadius(BIG.toPx()),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                        ),
                    )
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * Quiet relative-time label for the group divider. Under a minute reads as a
 * plain "now" (ICU would say the clunky "0 minutes ago"); beyond that ICU gives
 * a localised "5 minutes ago", "Yesterday", "3 days ago", and a date past a week.
 *
 * Not [android.text.format.DateUtils]: it formats in the phone's language, so
 * an Italian Ari on an English phone read "2 minutes ago" (ari-android#35).
 * [LocalLocale] carries the per-app locale.
 */
@Composable
private fun formatTimestamp(ts: Long): String {
    val elapsed = System.currentTimeMillis() - ts
    if (elapsed < DateUtils.MINUTE_IN_MILLIS) {
        return stringResource(R.string.conversation_timestamp_now)
    }
    val locale = ULocale.forLocale(LocalLocale.current.platformLocale)
    val relative = remember(locale) {
        RelativeDateTimeFormatter.getInstance(
            locale,
            null,
            RelativeDateTimeFormatter.Style.LONG,
            DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE,
        )
    }
    val days = elapsed / DateUtils.DAY_IN_MILLIS
    return when {
        elapsed < DateUtils.HOUR_IN_MILLIS -> relative.format(
            (elapsed / DateUtils.MINUTE_IN_MILLIS).toDouble(),
            RelativeDateTimeFormatter.Direction.LAST,
            RelativeDateTimeFormatter.RelativeUnit.MINUTES,
        )
        elapsed < DateUtils.DAY_IN_MILLIS -> relative.format(
            (elapsed / DateUtils.HOUR_IN_MILLIS).toDouble(),
            RelativeDateTimeFormatter.Direction.LAST,
            RelativeDateTimeFormatter.RelativeUnit.HOURS,
        )
        days == 1L -> relative.format(
            RelativeDateTimeFormatter.Direction.LAST,
            RelativeDateTimeFormatter.AbsoluteUnit.DAY,
        )
        days < 7 -> relative.format(
            days.toDouble(),
            RelativeDateTimeFormatter.Direction.LAST,
            RelativeDateTimeFormatter.RelativeUnit.DAYS,
        )
        else -> DateFormat.getInstanceForSkeleton(DateFormat.ABBR_MONTH_DAY, locale).format(Date(ts))
    }
}

/**
 * The subtle keyboard/mic glyph shown in a user message's trailing gutter,
 * indicating whether the turn was typed or spoken.
 */
@Composable
private fun MessageModalityGlyph(
    glyph: ModalityGlyph,
    modifier: Modifier = Modifier,
) {
    val (icon, descRes) = when (glyph) {
        ModalityGlyph.Typed -> Icons.Default.Keyboard to R.string.msg_source_typed
        ModalityGlyph.Voice -> Icons.Default.Mic to R.string.msg_source_voice
    }
    Icon(
        imageVector = icon,
        contentDescription = stringResource(descRes),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        modifier = modifier.size(16.dp),
    )
}
