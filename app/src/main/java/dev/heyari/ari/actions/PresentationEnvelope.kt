package dev.heyari.ari.actions

import android.util.Log
import dev.heyari.ari.data.card.Card
import dev.heyari.ari.data.card.CardAction
import dev.heyari.ari.data.card.OnComplete
import dev.heyari.ari.notifications.AlertAction
import dev.heyari.ari.notifications.AlertSpec
import dev.heyari.ari.notifications.NotificationAction
import dev.heyari.ari.notifications.NotificationPrimitive
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsed, typed view of a presentation envelope from a skill.
 *
 * The wire format is documented in
 * [docs/action-responses.md](https://github.com/ari-digital-assistant/ari-skills/blob/main/docs/action-responses.md).
 * `skillId` is supplied by the FFI layer (it's not in the envelope itself);
 * the parser stamps it onto every primitive that needs to resolve assets.
 *
 * Returns null on protocol-version mismatch or anything malformed enough that
 * we can't safely apply the envelope. Callers fall back to "I couldn't
 * understand that action."
 */
data class PresentationEnvelope(
    val speak: String?,
    val cards: List<Card>,
    val alerts: List<AlertSpec>,
    val notifications: List<NotificationPrimitive>,
    val launchApp: String?,
    val search: String?,
    val openUrl: String?,
    val clipboardText: String?,
    val createReminder: CreateReminderSpec?,
    val dismissCardIds: List<String>,
    val dismissNotificationIds: List<String>,
    val dismissAlertIds: List<String>,
    /**
     * How sure the emitting skill is about its own output. Generic
     * envelope-level signal (Layer A of the parse-confidence work):
     * `HIGH` is the default when the skill didn't say — keeps old
     * skill builds compatible. Other skills can opt in by emitting
     * the same two fields at envelope top-level.
     */
    val confidence: ParseConfidence,
    /** The residue phrase the skill noticed but couldn't consume. Null when [confidence] is HIGH. */
    val unparsed: String?,
) {
    enum class ParseConfidence { HIGH, PARTIAL, LOW }

    fun hasPresentationPrimitives(): Boolean =
        cards.isNotEmpty() || alerts.isNotEmpty() || notifications.isNotEmpty() ||
            dismissCardIds.isNotEmpty() || dismissNotificationIds.isNotEmpty() ||
            dismissAlertIds.isNotEmpty()

    companion object {
        const val SUPPORTED_VERSION: Int = 1

        fun parse(json: JSONObject, skillId: String): PresentationEnvelope? {
            val v = json.optInt("v", 0)
            if (v != SUPPORTED_VERSION) {
                Log.w(TAG, "envelope rejected — version $v != $SUPPORTED_VERSION")
                return null
            }
            return runCatching {
                PresentationEnvelope(
                    speak = json.optStringOrNull("speak"),
                    cards = json.optJSONArray("cards")?.let { parseCards(it, skillId) }.orEmpty(),
                    alerts = json.optJSONArray("alerts")?.let { parseAlerts(it, skillId) }.orEmpty(),
                    notifications = json.optJSONArray("notifications")
                        ?.let { parseNotifications(it, skillId) }.orEmpty(),
                    launchApp = json.optStringOrNull("launch_app"),
                    search = json.optStringOrNull("search"),
                    openUrl = json.optStringOrNull("open_url"),
                    clipboardText = json.optJSONObject("clipboard")?.optStringOrNull("text"),
                    createReminder = json.optJSONObject("create_reminder")
                        ?.let(CreateReminderSpec::parse),
                    dismissCardIds = json.optJSONObject("dismiss")
                        ?.optJSONArray("cards")?.toStringList().orEmpty(),
                    dismissNotificationIds = json.optJSONObject("dismiss")
                        ?.optJSONArray("notifications")?.toStringList().orEmpty(),
                    dismissAlertIds = json.optJSONObject("dismiss")
                        ?.optJSONArray("alerts")?.toStringList().orEmpty(),
                    confidence = parseConfidence(json.optStringOrNull("confidence")),
                    unparsed = json.optStringOrNull("unparsed"),
                )
            }.onFailure {
                Log.w(TAG, "envelope parse failed", it)
            }.getOrNull()
        }

        private fun parseConfidence(raw: String?): ParseConfidence = when (raw?.lowercase()) {
            "partial" -> ParseConfidence.PARTIAL
            "low" -> ParseConfidence.LOW
            // Unknown / missing → assume HIGH so pre-signal skills work unchanged.
            else -> ParseConfidence.HIGH
        }

        private const val TAG = "PresentationEnvelope"
    }
}

// -- Per-primitive parsers ------------------------------------------------

private fun parseCards(arr: JSONArray, skillId: String): List<Card> {
    val out = ArrayList<Card>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optStringOrNull("id") ?: continue
        val title = o.optStringOrNull("title") ?: continue
        out += Card(
            id = id,
            skillId = skillId,
            title = title,
            subtitle = o.optStringOrNull("subtitle"),
            body = o.optStringOrNull("body"),
            icon = o.optStringOrNull("icon"),
            countdownToTsMs = o.optLongOrNull("countdown_to_ts_ms"),
            startedAtTsMs = o.optLongOrNull("started_at_ts_ms"),
            progress = o.optJSONObject("progress")?.optDoubleOrNull("value")?.toFloat(),
            accent = parseAccent(o.optStringOrNull("accent")),
            actions = o.optJSONArray("actions")?.let { parseCardActions(it) }.orEmpty(),
            onComplete = o.optJSONObject("on_complete")?.let { parseOnComplete(it, skillId) },
        )
    }
    return out
}

private fun parseAccent(s: String?): Card.Accent = when (s) {
    "warning" -> Card.Accent.WARNING
    "success" -> Card.Accent.SUCCESS
    "critical" -> Card.Accent.CRITICAL
    else -> Card.Accent.DEFAULT
}

private fun parseCardActions(arr: JSONArray): List<CardAction> {
    val out = ArrayList<CardAction>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optStringOrNull("id") ?: continue
        val label = o.optStringOrNull("label") ?: continue
        out += CardAction(
            id = id,
            label = label,
            utterance = o.optStringOrNull("utterance"),
            style = parseCardStyle(o.optStringOrNull("style")),
        )
    }
    return out
}

private fun parseCardStyle(s: String?): CardAction.Style = when (s) {
    "primary" -> CardAction.Style.PRIMARY
    "destructive" -> CardAction.Style.DESTRUCTIVE
    else -> CardAction.Style.DEFAULT
}

private fun parseOnComplete(o: JSONObject, skillId: String): OnComplete = OnComplete(
    alert = o.optJSONObject("alert")?.let { parseAlert(it, skillId) },
    // Wire format default: dismiss_card true when on_complete is present.
    dismissCard = o.optBoolean("dismiss_card", true),
    dismissNotificationIds = o.optJSONArray("dismiss_notifications")?.toStringList().orEmpty(),
)

private fun parseAlerts(arr: JSONArray, skillId: String): List<AlertSpec> {
    val out = ArrayList<AlertSpec>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        parseAlert(o, skillId)?.let { out += it }
    }
    return out
}

private fun parseAlert(o: JSONObject, skillId: String): AlertSpec? {
    val id = o.optStringOrNull("id") ?: return null
    val title = o.optStringOrNull("title") ?: return null
    return AlertSpec(
        id = id,
        skillId = skillId,
        title = title,
        body = o.optStringOrNull("body"),
        urgency = parseUrgency(o.optStringOrNull("urgency")),
        sound = o.optStringOrNull("sound") ?: AlertSpec.SoundToken.NOTIFICATION,
        speechLoop = o.optStringOrNull("speech_loop"),
        autoStopMs = o.optLongOrNull("auto_stop_ms") ?: DEFAULT_AUTO_STOP_MS,
        maxCycles = o.optInt("max_cycles", DEFAULT_MAX_CYCLES),
        fullTakeover = o.optBoolean("full_takeover", false),
        icon = o.optStringOrNull("icon"),
        actions = o.optJSONArray("actions")?.let { parseAlertActions(it) }.orEmpty(),
    )
}

private fun parseUrgency(s: String?): AlertSpec.Urgency = when (s) {
    "critical" -> AlertSpec.Urgency.CRITICAL
    "high" -> AlertSpec.Urgency.HIGH
    else -> AlertSpec.Urgency.NORMAL
}

private fun parseAlertActions(arr: JSONArray): List<AlertAction> {
    val out = ArrayList<AlertAction>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optStringOrNull("id") ?: continue
        val label = o.optStringOrNull("label") ?: continue
        out += AlertAction(
            id = id,
            label = label,
            utterance = o.optStringOrNull("utterance"),
            style = parseAlertStyle(o.optStringOrNull("style")),
        )
    }
    return out
}

private fun parseAlertStyle(s: String?): AlertAction.Style = when (s) {
    "primary" -> AlertAction.Style.PRIMARY
    "destructive" -> AlertAction.Style.DESTRUCTIVE
    else -> AlertAction.Style.DEFAULT
}

private fun parseNotifications(arr: JSONArray, skillId: String): List<NotificationPrimitive> {
    val out = ArrayList<NotificationPrimitive>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optStringOrNull("id") ?: continue
        val title = o.optStringOrNull("title") ?: continue
        out += NotificationPrimitive(
            id = id,
            skillId = skillId,
            title = title,
            body = o.optStringOrNull("body"),
            importance = parseImportance(o.optStringOrNull("importance")),
            sticky = o.optBoolean("sticky", false),
            countdownToTsMs = o.optLongOrNull("countdown_to_ts_ms"),
            actions = o.optJSONArray("actions")?.let { parseNotificationActions(it) }.orEmpty(),
        )
    }
    return out
}

private fun parseImportance(s: String?): NotificationPrimitive.Importance = when (s) {
    "min" -> NotificationPrimitive.Importance.MIN
    "low" -> NotificationPrimitive.Importance.LOW
    "high" -> NotificationPrimitive.Importance.HIGH
    else -> NotificationPrimitive.Importance.DEFAULT
}

private fun parseNotificationActions(arr: JSONArray): List<NotificationAction> {
    val out = ArrayList<NotificationAction>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optStringOrNull("id") ?: continue
        val label = o.optStringOrNull("label") ?: continue
        out += NotificationAction(
            id = id,
            label = label,
            utterance = o.optStringOrNull("utterance"),
        )
    }
    return out
}

// -- Tiny JSON helpers ----------------------------------------------------

/**
 * `JSONObject.optString` returns `""` for missing keys (and for JSONObject.NULL),
 * which makes nullable-string semantics impossible without these wrappers.
 */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key, Double.NaN).takeIf { !it.isNaN() }

private fun JSONArray.toStringList(): List<String> {
    val out = ArrayList<String>(length())
    for (i in 0 until length()) out += optString(i, "")
    return out.filter { it.isNotEmpty() }
}

private const val DEFAULT_AUTO_STOP_MS: Long = 120_000L
private const val DEFAULT_MAX_CYCLES: Int = 12

/**
 * Top-level `create_reminder` slot — emitted by the reminder skill
 * for "remind me to X" / "add Y to my Z list" utterances. The
 * frontend handler reads the user's destination + default-list
 * settings, resolves [when] against the local zone, fuzzy-matches
 * [listHint] (if any) against the user's actual lists, performs the
 * VTODO / VEVENT insert via [dev.heyari.ari.reminders.CalendarProvider]
 * / [dev.heyari.ari.reminders.TasksProvider], then substitutes the
 * placeholders in [speakTemplate] for the spoken response.
 */
data class CreateReminderSpec(
    val title: String,
    val whenSpec: WhenSpec,
    val listHint: String?,
    val speakTemplate: String?,
) {
    /**
     * Structured time descriptor. Mirrors the shapes the skill emits
     * (see `ari-skills/skills/reminder/SKILL.md`):
     *
     * - [None] — no time, always routes to Tasks regardless of the
     *   destination setting (calendar grids can't show a timeless event).
     * - [InSeconds] — relative offset from now ("in 30 minutes").
     * - [LocalClock] — absolute hour/minute on a particular day, in
     *   the device's local zone.
     * - [LocalClockOnWeekday] — absolute hour/minute on a named
     *   weekday ("at 3pm on Friday"). The skill can't compute the day
     *   offset because it doesn't know the host's local weekday, so
     *   this handler resolves to the next occurrence in local time.
     * - [DateOnly] — a date with no time-of-day ("tomorrow") → VTODO
     *   with due date but no due time.
     * - [DateOnlyWeekday] — a date on a named weekday with no time
     *   ("on Friday") → same resolution rules as [LocalClockOnWeekday]
     *   but without the time component.
     *
     * The weekday variants carry a `DayOfWeek` directly; `java.time`'s
     * own enum is nicer than a 0..6 index and saves the resolver a
     * conversion step.
     */
    sealed interface WhenSpec {
        data object None : WhenSpec
        data class InSeconds(val seconds: Long) : WhenSpec
        data class LocalClock(val hour: Int, val minute: Int, val dayOffset: Int) : WhenSpec
        data class LocalClockOnWeekday(
            val hour: Int,
            val minute: Int,
            val weekday: java.time.DayOfWeek,
        ) : WhenSpec
        /** Absolute clock + calendar date ("at 10am on the 27th of April"). */
        data class LocalClockOnDate(
            val hour: Int,
            val minute: Int,
            val month: Int,
            val day: Int,
        ) : WhenSpec
        data class DateOnly(val dayOffset: Int) : WhenSpec
        data class DateOnlyWeekday(val weekday: java.time.DayOfWeek) : WhenSpec
        /** Calendar date with no time-of-day ("on the 27th of April"). */
        data class DateOnlyDate(val month: Int, val day: Int) : WhenSpec
    }

    companion object {
        fun parse(o: JSONObject): CreateReminderSpec? {
            val title = o.optStringOrNull("title")?.takeIf { it.isNotBlank() } ?: return null
            val whenSpec = parseWhen(o.opt("when"))
            return CreateReminderSpec(
                title = title,
                whenSpec = whenSpec,
                listHint = o.optStringOrNull("list_hint"),
                speakTemplate = o.optStringOrNull("speak_template"),
            )
        }

        private fun parseWhen(any: Any?): WhenSpec {
            // The skill emits `null` when no time was given. JSONObject
            // surfaces that as JSONObject.NULL via .opt; treat both
            // null and NULL as the no-time case.
            if (any == null || any == JSONObject.NULL) return WhenSpec.None
            val obj = any as? JSONObject ?: return WhenSpec.None

            obj.optLongOrNull("in_seconds")?.let { return WhenSpec.InSeconds(it) }

            val localTime = obj.optStringOrNull("local_time")
            val weekdayName = obj.optStringOrNull("weekday")
            val weekday = weekdayName?.let(::parseWeekday)
            val calendarDate = parseCalendarDate(obj)

            if (localTime != null) {
                val parts = localTime.split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull()
                val minute = parts.getOrNull(1)?.toIntOrNull()
                if (hour != null && minute != null) {
                    // Priority matches the skill-side priority:
                    // calendar date > weekday > day_offset. The skill
                    // only emits one shape at a time today, but this
                    // belt-and-braces so future ambiguous payloads
                    // land on the most specific semantic.
                    return when {
                        calendarDate != null -> WhenSpec.LocalClockOnDate(
                            hour,
                            minute,
                            calendarDate.first,
                            calendarDate.second,
                        )
                        weekday != null -> WhenSpec.LocalClockOnWeekday(hour, minute, weekday)
                        else -> WhenSpec.LocalClock(hour, minute, obj.optInt("day_offset", 0))
                    }
                }
            }

            // Date-only shapes: prefer calendar date, then weekday,
            // then day_offset. Only treat the block as date-only if
            // one of those fields is explicitly present; otherwise a
            // malformed `when` shouldn't accidentally create a
            // spurious due-date entry.
            if (calendarDate != null) {
                return WhenSpec.DateOnlyDate(calendarDate.first, calendarDate.second)
            }
            if (weekday != null) {
                return WhenSpec.DateOnlyWeekday(weekday)
            }
            if (obj.has("day_offset")) {
                return WhenSpec.DateOnly(obj.optInt("day_offset", 0))
            }

            return WhenSpec.None
        }

        /** Pull `month` + `day` out of a `when` block, or null if either is missing or invalid. */
        private fun parseCalendarDate(obj: JSONObject): Pair<Int, Int>? {
            if (!obj.has("month") || !obj.has("day")) return null
            val month = obj.optInt("month", 0).takeIf { it in 1..12 } ?: return null
            val day = obj.optInt("day", 0).takeIf { it in 1..31 } ?: return null
            return month to day
        }

        private fun parseWeekday(name: String): java.time.DayOfWeek? = when (name.lowercase()) {
            "monday" -> java.time.DayOfWeek.MONDAY
            "tuesday" -> java.time.DayOfWeek.TUESDAY
            "wednesday" -> java.time.DayOfWeek.WEDNESDAY
            "thursday" -> java.time.DayOfWeek.THURSDAY
            "friday" -> java.time.DayOfWeek.FRIDAY
            "saturday" -> java.time.DayOfWeek.SATURDAY
            "sunday" -> java.time.DayOfWeek.SUNDAY
            else -> null
        }
    }
}
