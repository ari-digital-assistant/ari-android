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
) {

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
                )
            }.onFailure {
                Log.w(TAG, "envelope parse failed", it)
            }.getOrNull()
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
     * Structured time descriptor. Mirrors the four shapes the skill
     * emits (see `ari-skills/skills/reminder/SKILL.md`):
     *
     * - [None] — no time, always routes to Tasks regardless of the
     *   destination setting (calendar grids can't show a timeless event).
     * - [InSeconds] — relative offset from now ("in 30 minutes").
     * - [LocalClock] — absolute hour/minute on a particular day, in
     *   the device's local zone.
     * - [DateOnly] — a date with no time-of-day ("tomorrow") → VTODO
     *   with due date but no due time.
     */
    sealed interface WhenSpec {
        data object None : WhenSpec
        data class InSeconds(val seconds: Long) : WhenSpec
        data class LocalClock(val hour: Int, val minute: Int, val dayOffset: Int) : WhenSpec
        data class DateOnly(val dayOffset: Int) : WhenSpec
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
            val dayOffset = obj.optInt("day_offset", 0)
            if (localTime != null) {
                // local_time is "HH:MM". Skill validates the format
                // before emitting; we still defend against a malformed
                // value rather than crashing the action handler.
                val parts = localTime.split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull()
                val minute = parts.getOrNull(1)?.toIntOrNull()
                if (hour != null && minute != null) {
                    return WhenSpec.LocalClock(hour, minute, dayOffset)
                }
            }

            // day_offset alone → DateOnly. Only treat as date-only
            // if the field was explicitly present; otherwise we'd
            // misinterpret a malformed `when` block as "today" and
            // create a spurious due-date entry.
            if (obj.has("day_offset")) {
                return WhenSpec.DateOnly(dayOffset)
            }

            return WhenSpec.None
        }
    }
}
