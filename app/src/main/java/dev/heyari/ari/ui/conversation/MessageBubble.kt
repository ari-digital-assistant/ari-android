package dev.heyari.ari.ui.conversation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.heyari.ari.assets.AssetResolver
import dev.heyari.ari.data.card.CardAction
import dev.heyari.ari.data.card.CardStateRepository
import dev.heyari.ari.model.Attachment

private val BIG = 18.dp
private val TAIL = 4.dp

@Composable
fun MessageBubble(
    row: MessageRow,
    modifier: Modifier = Modifier,
    cardRepository: CardStateRepository? = null,
    assetResolver: AssetResolver? = null,
    onCardAction: (cardId: String, action: CardAction) -> Unit = { _, _ -> },
) {
    val message = row.message
    val isUser = message.isFromUser

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

            Surface(
                shape = shape,
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = if (isUser) 0.dp else 2.dp,
                modifier = Modifier.widthIn(max = 300.dp),
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
 * Quiet relative-time label ("5 minutes ago") for the group divider.
 * [android.text.format.DateUtils] handles localisation for us.
 */
private fun formatTimestamp(ts: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(
        ts,
        System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
