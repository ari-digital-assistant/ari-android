package dev.heyari.ari.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.heyari.ari.data.timer.Timer
import dev.heyari.ari.data.timer.TimerStateRepository
import dev.heyari.ari.model.Attachment
import dev.heyari.ari.model.Message

@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    timerRepository: TimerStateRepository? = null,
    onCancelTimer: (Timer) -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isFromUser)
            androidx.compose.ui.Alignment.End
        else
            androidx.compose.ui.Alignment.Start,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (message.isFromUser) Arrangement.End else Arrangement.Start,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (message.isFromUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = if (message.isFromUser) 0.dp else 2.dp,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (message.isFromUser)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        for (attachment in message.attachments) {
            Spacer(Modifier.height(6.dp))
            when (attachment) {
                is Attachment.Timer -> {
                    if (timerRepository != null) {
                        val timerFlow = remember(attachment.timerId, timerRepository) {
                            timerRepository.observe(attachment.timerId)
                        }
                        val timer = timerFlow.collectAsState(initial = null).value
                        TimerCard(
                            timer = timer,
                            onCancel = onCancelTimer,
                            fallbackName = attachment.name,
                        )
                    }
                }
            }
        }
    }
}
