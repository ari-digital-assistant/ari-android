package dev.heyari.ari.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R

/**
 * Adaptive empty state for the conversation home screen. Shown in place of
 * the message list when there's no conversation yet. Two faces, chosen by
 * [mode]:
 *
 *  - [EmptyMode.FirstRun] — zero skills installed. Leads with a "Hi, I'm
 *    Ari" intro and a Browse-skills call-to-action card, plus a nudge that
 *    the user can just start typing.
 *  - [EmptyMode.SetUp] — at least one skill installed. Shows a (optionally
 *    name- and time-of-day-aware) greeting and a row of suggestion chips
 *    sourced generically from installed skills' declared examples. Tapping a
 *    chip submits it immediately as a turn.
 *
 * All copy comes from string resources so the whole surface is translatable;
 * the greeting in particular is mapped from a [GreetingModel] to a
 * `stringResource` here rather than assembled in Kotlin. Colours are M3
 * roles only — Dynamic Material You is preserved.
 */
@Composable
fun EmptyState(
    mode: EmptyMode,
    greeting: GreetingModel,
    chips: List<String>,
    onChip: (String) -> Unit,
    onBrowseSkills: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        when (mode) {
            EmptyMode.FirstRun -> {
                Text(
                    text = stringResource(R.string.empty_firstrun_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.empty_firstrun_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Card(
                    onClick = onBrowseSkills,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("✨  ", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(R.string.empty_firstrun_cta),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text("→", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.empty_firstrun_or),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            EmptyMode.SetUp -> {
                Text(
                    text = greetingText(greeting),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.empty_setup_sub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                SuggestionChips(chips = chips, onChip = onChip)
            }
        }
    }
}

/**
 * Wrapping row of suggestion chips. Uses Compose Foundation's [FlowRow]
 * (matching the capabilities row in the skill detail screen) so chips wrap
 * onto new lines on narrow devices rather than overflowing the edge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChips(chips: List<String>, onChip: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (chip in chips) {
            SuggestionChip(
                onClick = { onChip(chip) },
                label = { Text(chip) },
            )
        }
    }
}

/**
 * Map a [GreetingModel] onto a translatable greeting string. The anonymous
 * face is a plain "Hi, I'm Ari"; the named face picks the time-of-day
 * variant and interpolates the name. Kept as the single place the greeting
 * becomes user-facing text so the wording stays translatable.
 */
@Composable
private fun greetingText(g: GreetingModel): String = when (g) {
    GreetingModel.Anonymous -> stringResource(R.string.empty_greeting_anon)
    is GreetingModel.Named -> when (g.part) {
        DayPart.MORNING -> stringResource(R.string.empty_greeting_morning, g.name)
        DayPart.AFTERNOON -> stringResource(R.string.empty_greeting_afternoon, g.name)
        DayPart.EVENING -> stringResource(R.string.empty_greeting_evening, g.name)
    }
}
