package dev.heyari.ari.ui.conversation

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R
import dev.heyari.ari.assets.AssetResolver
import dev.heyari.ari.data.card.CardAction
import dev.heyari.ari.data.card.CardStateRepository
import dev.heyari.ari.model.Attachment
import dev.heyari.ari.model.Message

private val BIG = 18.dp
private val TAIL = 4.dp

@Composable
fun MessageBubble(
    row: MessageRow,
    modifier: Modifier = Modifier,
    cardRepository: CardStateRepository? = null,
    assetResolver: AssetResolver? = null,
    onCardAction: (cardId: String, action: CardAction) -> Unit = { _, _ -> },
    onReport: (Message) -> Unit = {},
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
            // on a chat message; Report is only on Ari's, because reporting your
            // own words to the developer is meaningless.
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
                // IntrinsicSize.Min lets the accent bar match the text's height
                // exactly; the enclosing Surface clips it to the bubble's shape,
                // so the bar rounds with the corner.
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    if (!isUser) {
                        // Hairline accent edge on Ari turns.
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.message_menu_copy)) },
                    onClick = {
                        menuOpen = false
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText(null, message.text))
                    },
                )
                if (!isUser) {
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
                }
            }
        }
    }
}

/**
 * Quiet relative-time label for the group divider. Under a minute reads as a
 * plain "now" (DateUtils would say the clunky "0 minutes ago"); beyond that
 * [android.text.format.DateUtils] gives a localised "5 minutes ago" etc.
 */
@Composable
private fun formatTimestamp(ts: Long): String {
    val now = System.currentTimeMillis()
    return if (now - ts < android.text.format.DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.conversation_timestamp_now)
    } else {
        android.text.format.DateUtils.getRelativeTimeSpanString(
            ts,
            now,
            android.text.format.DateUtils.MINUTE_IN_MILLIS,
        ).toString()
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
