package dev.heyari.ari.ui.conversation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.heyari.ari.R
import dev.heyari.ari.listening.ListeningMode

/**
 * The listening control for the conversation top bar: three icon-only
 * segments that set [ListeningMode] directly — mic-off / calendar / mic.
 *
 * There is no separate pause layered on top of the mode (see
 * [dev.heyari.ari.listening.decideListening]'s doc comment for why): tapping
 * the mic-off segment sets [ListeningMode.NEVER] outright. That's not lossy —
 * it never touches the stored Custom conditions, schedules or places, so
 * tapping the calendar segment afterwards restores exactly what was
 * configured.
 *
 * The middle segment reads as "on a schedule" even though [ListeningMode.CUSTOM]
 * can be built from any of five conditions, schedule included or not — the
 * calendar glyph is evocative rather than literal, matching the no-label brief
 * this control was asked for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningModeSwitch(
    mode: ListeningMode,
    onSelect: (ListeningMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments: List<Pair<ListeningMode, ImageVector>> = listOf(
        ListeningMode.NEVER to Icons.Default.MicOff,
        ListeningMode.CUSTOM to Icons.Default.CalendarMonth,
        ListeningMode.ALWAYS to Icons.Default.Mic,
    )

    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        segments.forEachIndexed { index, (segmentMode, icon) ->
            SegmentedButton(
                selected = mode == segmentMode,
                onClick = { onSelect(segmentMode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = segments.size),
                icon = {},
                label = {
                    Icon(
                        imageVector = icon,
                        contentDescription = stringResource(descriptionRes(segmentMode)),
                    )
                },
            )
        }
    }
}

private fun descriptionRes(mode: ListeningMode): Int = when (mode) {
    ListeningMode.NEVER -> R.string.listening_mode_switch_never_description
    ListeningMode.CUSTOM -> R.string.listening_mode_switch_custom_description
    ListeningMode.ALWAYS -> R.string.listening_mode_switch_always_description
}
