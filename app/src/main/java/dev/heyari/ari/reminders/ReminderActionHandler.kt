package dev.heyari.ari.reminders

import android.util.Log
import dev.heyari.ari.actions.CreateReminderSpec
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.SkillRegistry

/**
 * Performs the actual `create_reminder` side-effect described by the
 * skill's [CreateReminderSpec]. The skill stays timezone-naive — this
 * handler resolves the structured `when` descriptor against the
 * device's local zone, picks a destination (Tasks / Calendar / Both)
 * based on the user's setting, fuzzy-matches the optional list hint
 * against their actual calendars / task lists, and inserts the row.
 *
 * Returns the spoken response text — `speak_template` from the spec
 * with `{title}` and `{list_name}` / `{calendar_name}` substituted
 * for the resolved destination's display name. The caller hands that
 * to TTS.
 */
@Singleton
class ReminderActionHandler @Inject constructor(
    private val skillRegistry: SkillRegistry,
    private val calendarProvider: CalendarProvider,
    private val tasksProvider: TasksProvider,
) {

    fun handle(spec: CreateReminderSpec): String {
        // Read settings via the same FFI call the per-skill detail
        // page uses. Returns the unified schema with currentValue
        // populated from the shared in-memory store, so a value the
        // user just tapped in the picker is immediately visible here
        // without needing a separate hydration step.
        val settings = runCatching { skillRegistry.getSkillSettings(REMINDER_SKILL_ID) }
            .getOrDefault(emptyList())
            .associate { it.key to it.currentValue }

        val destination = settings["destination"] ?: "tasks"
        val resolved = resolveWhen(spec.whenSpec)

        // Untimed always routes to Tasks regardless of the destination
        // setting. Calendar grids have no useful representation for an
        // event with no time, and writing one as a 30-minute event at
        // some arbitrary default would surprise the user.
        val effectiveDestination = if (resolved.isUntimed) "tasks" else destination

        val result = when (effectiveDestination) {
            "tasks" -> insertIntoTasks(spec, settings, resolved)
            "calendar" -> insertIntoCalendar(spec, settings, resolved)
            "both" -> {
                // Try both, prefer the calendar's display name in the
                // spoken reply since that's the more visible
                // destination. If either side fails the other still
                // succeeds — degrades to a single-destination outcome
                // rather than a hard failure. Note: the confirmation
                // card's Cancel in partial-confidence flows only rolls
                // back the chosen primary (calendar here); a "both"
                // destination user has to tidy the task row manually.
                val taskOutcome = insertIntoTasks(spec, settings, resolved)
                val calOutcome = insertIntoCalendar(spec, settings, resolved)
                calOutcome.takeIf { it !is Outcome.Failure } ?: taskOutcome
            }
            else -> {
                Log.w(TAG, "unknown destination=$destination — falling back to Tasks")
                insertIntoTasks(spec, settings, resolved)
            }
        }

        return formatSpeech(spec, result)
    }

    // ── Resolution ──────────────────────────────────────────────────

    private data class ResolvedWhen(
        /** UTC epoch ms, or null for date-only / untimed. */
        val absoluteMillis: Long?,
        /** True when the spec has no time component at all. */
        val isUntimed: Boolean,
        /** True when the spec is date-only (day, no time-of-day). */
        val isDateOnly: Boolean,
    )

    private fun resolveWhen(whenSpec: CreateReminderSpec.WhenSpec): ResolvedWhen {
        val zone = ZoneId.systemDefault()
        return when (whenSpec) {
            CreateReminderSpec.WhenSpec.None -> ResolvedWhen(null, isUntimed = true, isDateOnly = false)

            is CreateReminderSpec.WhenSpec.InSeconds -> {
                val ms = System.currentTimeMillis() + whenSpec.seconds * 1000L
                ResolvedWhen(ms, isUntimed = false, isDateOnly = false)
            }

            is CreateReminderSpec.WhenSpec.LocalClock -> {
                // Defensive bump: if the user said "at 5pm" and it's
                // already past 5pm locally, push to tomorrow rather
                // than scheduling something that fires immediately
                // and confuses everyone. Only applies when the skill
                // emitted day_offset=0; explicit "tomorrow at 3am"
                // (day_offset=1) is honoured as-is.
                var date = LocalDate.now(zone).plusDays(whenSpec.dayOffset.toLong())
                val time = LocalTime.of(whenSpec.hour, whenSpec.minute)
                if (whenSpec.dayOffset == 0 && LocalDateTime.of(date, time).isBefore(LocalDateTime.now(zone))) {
                    date = date.plusDays(1)
                }
                val ms = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
                ResolvedWhen(ms, isUntimed = false, isDateOnly = false)
            }

            is CreateReminderSpec.WhenSpec.LocalClockOnWeekday -> {
                // "at 3pm on Friday": land on the next local-time
                // Friday that still has the requested time in the
                // future. If today matches the target weekday and
                // the time is already past, bump to next week —
                // same defensive behaviour as plain LocalClock, just
                // with a 7-day bump instead of 1.
                val today = LocalDate.now(zone)
                val time = LocalTime.of(whenSpec.hour, whenSpec.minute)
                val daysAhead = daysUntilWeekday(today.dayOfWeek, whenSpec.weekday)
                var date = today.plusDays(daysAhead.toLong())
                if (daysAhead == 0 && LocalDateTime.of(date, time).isBefore(LocalDateTime.now(zone))) {
                    date = date.plusDays(7)
                }
                val ms = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
                ResolvedWhen(ms, isUntimed = false, isDateOnly = false)
            }

            is CreateReminderSpec.WhenSpec.LocalClockOnDate -> {
                // "at 10am on the 27th of April": this year if the
                // requested moment is still in the future, next
                // year otherwise. The skill doesn't emit a year —
                // that's the frontend's job so different locales
                // (and users in different timezones) get the same
                // "next occurrence" interpretation of a bare date.
                val now = LocalDateTime.now(zone)
                val time = LocalTime.of(whenSpec.hour, whenSpec.minute)
                val date = resolveYear(now.toLocalDate(), whenSpec.month, whenSpec.day, time, now)
                val ms = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
                ResolvedWhen(ms, isUntimed = false, isDateOnly = false)
            }

            is CreateReminderSpec.WhenSpec.DateOnly -> {
                // Date-only: pick midnight at the start of the target
                // date in local time. The Tasks insert flips
                // is_allday on so the provider stores just the date
                // portion regardless of the exact instant.
                val date = LocalDate.now(zone).plusDays(whenSpec.dayOffset.toLong())
                val ms = date.atStartOfDay(zone).toInstant().toEpochMilli()
                ResolvedWhen(ms, isUntimed = false, isDateOnly = true)
            }

            is CreateReminderSpec.WhenSpec.DateOnlyWeekday -> {
                // Date-only weekday: next occurrence of the target
                // weekday, inclusive of today. "On Friday" said on a
                // Friday creates a task for today, which is the
                // closest reading of the user's intent without a
                // time-of-day to guide a bump decision.
                val today = LocalDate.now(zone)
                val daysAhead = daysUntilWeekday(today.dayOfWeek, whenSpec.weekday)
                val date = today.plusDays(daysAhead.toLong())
                val ms = date.atStartOfDay(zone).toInstant().toEpochMilli()
                ResolvedWhen(ms, isUntimed = false, isDateOnly = true)
            }

            is CreateReminderSpec.WhenSpec.DateOnlyDate -> {
                // Date-only calendar date. Without a time-of-day
                // there's no "has the moment already passed" comparison
                // — the bump is purely about date. Today at the target
                // month+day counts as still-current (user might be
                // setting a task for the whole of today); anything in
                // the past rolls to next year.
                val today = LocalDate.now(zone)
                val date = resolveYear(today, whenSpec.month, whenSpec.day, null, null)
                val ms = date.atStartOfDay(zone).toInstant().toEpochMilli()
                ResolvedWhen(ms, isUntimed = false, isDateOnly = true)
            }
        }
    }

    /**
     * Days from `today` (inclusive) to the next occurrence of `target`.
     * Returns 0 when they match — callers decide whether to bump.
     */
    private fun daysUntilWeekday(
        today: java.time.DayOfWeek,
        target: java.time.DayOfWeek,
    ): Int = ((target.value - today.value) + 7) % 7

    /**
     * Choose the year for a bare (month, day) so the resulting date is
     * in the future. If `time` and `now` are provided, the comparison
     * is against the exact moment; otherwise it's a date-only
     * comparison (midnight).
     *
     * Feb 29 in a non-leap year would normally throw — we catch
     * DateTimeException and roll forward until we land on a valid
     * leap year rather than crashing the action.
     */
    private fun resolveYear(
        today: LocalDate,
        month: Int,
        day: Int,
        time: LocalTime?,
        now: LocalDateTime?,
    ): LocalDate {
        var year = today.year
        // Try up to 4 years forward to handle Feb 29 near leap-year
        // boundaries. In practice one bump is almost always enough.
        repeat(4) {
            val candidate = runCatching { LocalDate.of(year, month, day) }.getOrNull()
            if (candidate != null) {
                val stillFuture = if (time != null && now != null) {
                    !LocalDateTime.of(candidate, time).isBefore(now)
                } else {
                    !candidate.isBefore(today)
                }
                if (stillFuture) return candidate
            }
            year += 1
        }
        // Shouldn't reach here for any sane input. Fall back to today
        // rather than throwing out of the action handler.
        Log.w(TAG, "resolveYear couldn't find a valid date for month=$month day=$day; using today")
        return today
    }

    // ── Destination dispatchers ────────────────────────────────────

    private sealed interface Outcome {
        data class TasksWritten(val listName: String) : Outcome
        data class CalendarWritten(val calendarName: String) : Outcome
        data class Failure(val reason: String) : Outcome
    }

    private fun insertIntoTasks(
        spec: CreateReminderSpec,
        settings: Map<String, String?>,
        resolved: ResolvedWhen,
    ): Outcome {
        if (!tasksProvider.isProviderInstalled()) {
            return Outcome.Failure(
                "I can't add tasks because no tasks app is installed. " +
                    "Open Skills → Reminder → Settings to install one.",
            )
        }

        val available = tasksProvider.listTaskLists()
        if (available.isEmpty()) {
            return Outcome.Failure(
                "Your tasks app doesn't have any lists set up yet. " +
                    "Open it and create a list, then try again.",
            )
        }

        val target = resolveListTarget(
            available = available,
            byId = { it.id.toString() },
            byName = { it.displayName },
            settings[DEFAULT_TASK_LIST_KEY],
            spec.listHint,
        )

        val taskId = tasksProvider.insertTask(
            taskListId = target.id,
            title = spec.title,
            dueMillis = resolved.absoluteMillis,
            dueAllDay = resolved.isDateOnly,
        )
        return if (taskId != null) {
            Outcome.TasksWritten(target.displayName)
        } else {
            Outcome.Failure("I couldn't save that task. Check that the tasks app has permission.")
        }
    }

    private fun insertIntoCalendar(
        spec: CreateReminderSpec,
        settings: Map<String, String?>,
        resolved: ResolvedWhen,
    ): Outcome {
        if (resolved.absoluteMillis == null) {
            // Caller should have routed untimed / date-only to Tasks
            // already. Defensive fallback: refuse rather than make up
            // an arbitrary event time.
            return Outcome.Failure("That reminder has no time, so I can't put it on the calendar.")
        }

        val available = calendarProvider.listCalendars()
        if (available.isEmpty()) {
            return Outcome.Failure(
                "I couldn't find any writable calendars. Grant calendar access first.",
            )
        }

        val target = resolveListTarget(
            available = available,
            byId = { it.id.toString() },
            byName = { it.displayName },
            settings[DEFAULT_CALENDAR_KEY],
            spec.listHint,
        )

        val eventId = calendarProvider.insertEvent(
            calendarId = target.id,
            title = spec.title,
            startMillis = resolved.absoluteMillis,
        )
        return if (eventId != null) {
            Outcome.CalendarWritten(target.displayName)
        } else {
            Outcome.Failure("I couldn't add that to the calendar. Check calendar permissions.")
        }
    }

    // ── List target resolution ─────────────────────────────────────

    /**
     * Pick the right calendar / task list given (a) the available
     * destinations, (b) the user's stored default for this destination
     * type, and (c) the optional voice-spoken hint. Voice always wins
     * over the static default — that's what lets the user say "add
     * milk to my shopping list" and bypass their default-list setting.
     *
     * Matching is case-insensitive and tolerates the spoken form being
     * a substring of the display name (e.g. "shopping" matches
     * "Shopping list" / "Shopping Personal"). On no match falls back
     * to the stored default; if that's also missing, picks the first
     * available entry so the user always gets *something* useful.
     *
     * The stored default is compared by provider id (what the picker
     * persists), but provider ids can drift on the emulator — Tasks.org
     * reinstall, data clear, different CalDAV account — leaving a
     * stored id that matches no list. Rather than silently slide onto
     * whatever `available.first()` is, we log the mismatch so it's
     * debuggable from logcat instead of showing up as "it went to the
     * wrong list" in a bug report.
     */
    private fun <T : Any> resolveListTarget(
        available: List<T>,
        byId: (T) -> String,
        byName: (T) -> String,
        storedDefault: String?,
        hint: String?,
    ): T {
        if (!hint.isNullOrBlank()) {
            val needle = hint.trim().lowercase()
            // Exact (case-insensitive) name match wins first.
            available.firstOrNull { byName(it).lowercase() == needle }?.let { return it }
            // Then substring match — handles "shopping" → "Shopping list".
            available.firstOrNull { byName(it).lowercase().contains(needle) }?.let { return it }
            Log.w(TAG, "voice hint '$hint' matched no list in ${available.map(byName)}; using default")
        }
        if (!storedDefault.isNullOrBlank()) {
            available.firstOrNull { byId(it) == storedDefault }?.let { return it }
            Log.w(
                TAG,
                "stored default id='$storedDefault' no longer matches any available list " +
                    "(ids=${available.map(byId)}, names=${available.map(byName)}); " +
                    "falling back to first available. User should re-select in skill settings.",
            )
        }
        return available.first()
    }

    // ── Speech formatting ──────────────────────────────────────────

    private fun formatSpeech(spec: CreateReminderSpec, result: Outcome): String {
        if (result is Outcome.Failure) return result.reason

        val template = spec.speakTemplate ?: DEFAULT_SPEAK_TEMPLATE
        val (placeholderKey, listOrCalendar) = when (result) {
            is Outcome.TasksWritten -> "{list_name}" to result.listName
            is Outcome.CalendarWritten -> "{calendar_name}" to result.calendarName
            is Outcome.Failure -> return result.reason
        }

        // Substitute both potential placeholders so a skill that
        // hardcoded one or the other still reads sensibly. Whichever
        // didn't fire stays as a literal in the template.
        return template
            .replace("{title}", spec.title)
            .replace(placeholderKey, listOrCalendar)
            // Cross-substitute the OTHER placeholder if the template
            // hardcoded `{list_name}` but we ended up writing to a
            // calendar (or vice versa). Keeps the user's spoken reply
            // readable instead of leaking a literal `{list_name}`
            // string into the TTS output.
            .let { partial ->
                val otherPlaceholder = if (placeholderKey == "{list_name}") "{calendar_name}" else "{list_name}"
                partial.replace(otherPlaceholder, listOrCalendar)
            }
    }

    companion object {
        const val REMINDER_SKILL_ID = "dev.heyari.reminder"
        private const val DEFAULT_TASK_LIST_KEY = "default_task_list"
        private const val DEFAULT_CALENDAR_KEY = "default_calendar"
        private const val DEFAULT_SPEAK_TEMPLATE = "Added {title} to your {list_name} list."
        private const val TAG = "ReminderActionHandler"
    }
}
