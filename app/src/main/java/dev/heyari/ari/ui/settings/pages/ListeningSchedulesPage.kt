package dev.heyari.ari.ui.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.listening.ListeningSchedule
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.components.SettingsScaffold
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

@Composable
fun ListeningSchedulesPage(
    onBack: () -> Unit,
    onOpenEditor: (String?) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_listening_schedules_title),
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_listening_schedules_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.listeningSchedules.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_listening_schedules_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.listeningSchedules.forEach { schedule ->
                ScheduleRow(
                    schedule = schedule,
                    onEdit = { onOpenEditor(schedule.id) },
                    onDelete = { viewModel.deleteListeningSchedule(schedule.id) },
                )
            }

            Button(
                onClick = { onOpenEditor(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_listening_schedule_add))
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    schedule: ListeningSchedule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = onEdit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatTimeRange(schedule),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatDays(schedule.days),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                )
            }
        }
    }
}

/**
 * Full screen rather than a dialog: seven day chips plus two time buttons
 * genuinely don't fit a dialog's constrained width without scrolling to see
 * Sunday. A dedicated destination gets the same width and insets handling as
 * every other settings page, for free.
 */
@Composable
fun ScheduleEditorScreen(
    scheduleId: String?,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val initial = remember(scheduleId, state.listeningSchedules) {
        state.listeningSchedules.firstOrNull { it.id == scheduleId }
    }

    val context = LocalContext.current
    val is24Hour = android.text.format.DateFormat.is24HourFormat(context)

    var days by remember(initial) { mutableStateOf(initial?.days ?: emptySet()) }
    var startMinute by remember(initial) { mutableStateOf(initial?.startMinute ?: DEFAULT_START_MINUTE) }
    var endMinute by remember(initial) { mutableStateOf(initial?.endMinute ?: DEFAULT_END_MINUTE) }
    var editingStart by remember { mutableStateOf(false) }
    var editingEnd by remember { mutableStateOf(false) }

    // An empty day set never fires, and equal times would mean a silent
    // twenty-four-hour window — refuse both rather than store a schedule that
    // does something other than what it reads like.
    val valid = days.isNotEmpty() && startMinute != endMinute

    SettingsScaffold(
        title = stringResource(
            if (scheduleId == null) R.string.settings_listening_schedule_add
            else R.string.settings_listening_schedule_edit
        ),
        onBack = onBack,
        actions = {
            TextButton(
                enabled = valid,
                onClick = {
                    viewModel.saveListeningSchedule(
                        ListeningSchedule(
                            id = scheduleId ?: UUID.randomUUID().toString(),
                            days = days,
                            startMinute = startMinute,
                            endMinute = endMinute,
                        )
                    )
                    onBack()
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_listening_schedule_days_label),
                style = MaterialTheme.typography.labelLarge,
            )
            // Read observably so the chips reorder and relabel if the locale
            // changes under us, rather than only on the next recreation.
            val locale = LocalLocale.current.platformLocale
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                localeOrderedDays(locale).forEach { day ->
                    FilterChip(
                        selected = day in days,
                        onClick = {
                            days = if (day in days) days - day else days + day
                        },
                        label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                    )
                }
            }

            Text(
                text = stringResource(R.string.settings_listening_schedule_hours_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { editingStart = true },
                    modifier = Modifier.weight(1f),
                ) { Text(formatMinuteOfDay(startMinute)) }
                OutlinedButton(
                    onClick = { editingEnd = true },
                    modifier = Modifier.weight(1f),
                ) { Text(formatMinuteOfDay(endMinute)) }
            }

            if (endMinute < startMinute) {
                Text(
                    text = stringResource(R.string.settings_listening_schedule_overnight_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (startMinute == endMinute) {
                Text(
                    text = stringResource(R.string.settings_listening_schedule_same_time_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (editingStart) {
        TimePickerDialog(
            initialMinute = startMinute,
            is24Hour = is24Hour,
            onDismiss = { editingStart = false },
            onConfirm = {
                startMinute = it
                editingStart = false
            },
        )
    }
    if (editingEnd) {
        TimePickerDialog(
            initialMinute = endMinute,
            is24Hour = is24Hour,
            onDismiss = { editingEnd = false },
            onConfirm = {
                endMinute = it
                editingEnd = false
            },
        )
    }
}

/**
 * A single time picker is a normal Material3 dialog size in every Android
 * app — that's not what "too small" meant. Only the multi-field editor above
 * needed the full-screen treatment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinute: Int,
    is24Hour: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = is24Hour,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour * 60 + pickerState.minute) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Weekdays starting from whatever the user's locale calls the first day —
 * Monday in most of Europe, Sunday in the US. Hard-coding Monday would look
 * wrong to half the world.
 */
private fun localeOrderedDays(locale: Locale = Locale.getDefault()): List<DayOfWeek> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return (0L..6L).map { first.plus(it) }
}

private fun formatDays(days: Set<DayOfWeek>): String =
    localeOrderedDays()
        .filter { it in days }
        .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }

private fun formatMinuteOfDay(minute: Int): String =
    LocalTime.of(minute / 60, minute % 60)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))

private fun formatTimeRange(schedule: ListeningSchedule): String =
    "${formatMinuteOfDay(schedule.startMinute)} – ${formatMinuteOfDay(schedule.endMinute)}"

private const val DEFAULT_START_MINUTE = 9 * 60
private const val DEFAULT_END_MINUTE = 17 * 60
