package dev.heyari.ari.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.heyari.ari.data.timer.Timer
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/**
 * Live countdown card that ticks once per second against wall-clock time.
 *
 * Intentionally does NOT use `android.widget.Chronometer` — that's a View and
 * compose interop is ugly. A cheap coroutine tick driven by `produceState`
 * composes cleanly and only runs while the card is on-screen, because
 * LazyColumn cancels child coroutines when items scroll out of view.
 *
 * Expired / cancelled / uninitialised states all collapse into a muted
 * "Done" chip so the bubble doesn't need special-case logic.
 */
@Composable
fun TimerCard(
    timer: Timer?,
    onCancel: (Timer) -> Unit,
    modifier: Modifier = Modifier,
    fallbackName: String? = null,
) {
    if (timer == null) {
        FinishedCard(label = doneLabel(fallbackName), modifier = modifier)
        return
    }

    val durationMs = (timer.endTsMs - timer.createdTsMs).coerceAtLeast(1)
    val remainingState = remainingTimeMs(endTsMs = timer.endTsMs)
    val remaining = remainingState.value

    if (remaining <= 0) {
        FinishedCard(
            label = doneLabel(timer.name ?: fallbackName),
            modifier = modifier,
        )
        return
    }

    val progress = 1f - (remaining.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 300.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = timer.name?.let { "${capitaliseFirst(it)} timer" } ?: "Timer",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                IconButton(
                    onClick = { onCancel(timer) },
                    modifier = Modifier.padding(0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Cancel timer",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
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
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FinishedCard(label: String, modifier: Modifier = Modifier) {
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
            // Tick at the next whole second boundary so the MM:SS display
            // doesn't jitter. `now % 1000` is our distance from the last
            // boundary; wait 1000 - that (clamped so we never sleep 0).
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

private fun capitaliseFirst(s: String): String =
    if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)

private fun doneLabel(name: String?): String =
    name?.takeIf { it.isNotBlank() }?.let { "${capitaliseFirst(it)} timer done" }
        ?: "Timer done"
