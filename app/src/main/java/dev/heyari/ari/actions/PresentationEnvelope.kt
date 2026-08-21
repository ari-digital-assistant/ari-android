package dev.heyari.ari.actions

import android.util.Log
import dev.heyari.ari.data.card.Card
import dev.heyari.ari.data.card.CardAction
import dev.heyari.ari.data.card.IconText
import dev.heyari.ari.data.card.ListCard
import dev.heyari.ari.data.card.ListRow
import dev.heyari.ari.data.card.OnComplete
import dev.heyari.ari.data.card.Stat
import dev.heyari.ari.notifications.AlertAction
import dev.heyari.ari.notifications.AlertSpec
import dev.heyari.ari.notifications.NotificationAction
import dev.heyari.ari.notifications.NotificationPrimitive
import org.json.JSONArray
import org.json.JSONObject

/** A media action from the engine, e.g. play a [query] optionally on a named [service]. */
data class MediaAction(
    val action: String,
    val query: String?,
    val service: String?,
    val direction: String? = null,
    val level: Int? = null,
    val mute: Boolean? = null,
)

/** A navigation action from the engine. `mode` is "default_app" (or null) / "turn_by_turn". */
data class NavigateAction(
    val destination: String,
    val mode: String?,
)

/**
 * A message action from the engine. [text] is the body as the user said it.
 *
 * [delivery] is a *request*, not an instruction: "send" asks for a true send,
 * "compose" asks to hand off to the app's own compose surface. Most services
 * offer no way to send on a user's behalf from another app, so a "send" will
 * often come back as a prepared message the user still has to tap. The
 * launcher reports what actually happened; nothing here should assume.
 *
 * [service] may be null when the user didn't name one, in which case the user
 * picks the app as well as the recipient. [recipientLabel] is for speaking
 * back to the user only — [recipientId] is the one that addresses anything.
 */
data class MessageAction(
    val text: String,
    val service: String?,
    val recipientId: String?,
    val recipientLabel: String?,
    val delivery: String?,
)

/**
 * A reply into a conversation that already has a live notification.
 *
 * [recipientLabel] is null when the user didn't name anybody — "reply, on my
 * way" means the newest thread, which is the hands-free case this exists for.
 */
data class ReplyAction(
    val text: String,
    val recipientLabel: String?,
)

/** An alarm action from the engine. `op` is "set" or "show". */
data class AlarmAction(
    val op: String,
    val hour: Int?,
    val minute: Int?,
    val message: String?,
    val days: List<String>,
    /** Whether to create the alarm without showing the Clock UI. Defaults to
     *  true (the SDK always emits it); a skill may set it false to surface the
     *  Clock app's own confirm UI. */
    val skipUi: Boolean = true,
)

/**
 * Parsed, typed view of a presentation envelope from a skill.
 *
 * The wire format is documented in
 * [docs/reference-actions.md](https://github.com/ari-digital-assistant/ari-skills/blob/main/docs/reference-actions.md).
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
    val media: MediaAction?,
    val alarm: AlarmAction?,
    val navigate: NavigateAction?,
    val message: MessageAction?,
    val reply: ReplyAction?,
    val search: String?,
    val openUrl: String?,
    val clipboardText: String?,
    val dismissCardIds: List<String>,
    val dismissNotificationIds: List<String>,
    val dismissAlertIds: List<String>,
    /**
     * Optional utterance the frontend re-dispatches through the engine
     * after handling the rest of the envelope. Useful inside a card's
     * `on_cancel` payload so a skill can round-trip a cancel back to
     * itself without needing a skill-specific envelope primitive.
     */
    val runUtterance: String?,
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
                    media = json.optJSONObject("media")?.let { o ->
                        o.optStringOrNull("action")?.let { act ->
                            MediaAction(
                                action = act,
                                query = o.optStringOrNull("query"),
                                service = o.optStringOrNull("service"),
                                direction = o.optStringOrNull("direction"),
                                level = if (o.has("level")) o.optInt("level") else null,
                                mute = if (o.has("mute")) o.optBoolean("mute") else null,
                            )
                        }
                    },
                    alarm = json.optJSONObject("alarm")?.let { o ->
                        o.optStringOrNull("op")?.let { op ->
                            AlarmAction(
                                op = op,
                                hour = if (o.has("hour")) o.optInt("hour") else null,
                                minute = if (o.has("minute")) o.optInt("minute") else null,
                                message = o.optStringOrNull("message"),
                                days = o.optJSONArray("days")?.toStringList().orEmpty(),
                                skipUi = if (o.has("skip_ui")) o.optBoolean("skip_ui") else true,
                            )
                        }
                    },
                    navigate = json.optJSONObject("navigate")?.let { o ->
                        o.optStringOrNull("destination")?.let { dest ->
                            NavigateAction(
                                destination = dest,
                                mode = o.optStringOrNull("mode"),
                            )
                        }
                    },
                    message = json.optJSONObject("message")?.let { o ->
                        o.optStringOrNull("text")?.let { body ->
                            MessageAction(
                                text = body,
                                service = o.optStringOrNull("service"),
                                recipientId = o.optStringOrNull("recipient_id"),
                                recipientLabel = o.optStringOrNull("recipient_label"),
                                delivery = o.optStringOrNull("delivery"),
                            )
                        }
                    },
                    reply = json.optJSONObject("reply")?.let { o ->
                        o.optStringOrNull("text")?.let { body ->
                            ReplyAction(
                                text = body,
                                recipientLabel = o.optStringOrNull("recipient_label"),
                            )
                        }
                    },
                    search = json.optStringOrNull("search"),
                    openUrl = json.optStringOrNull("open_url"),
                    clipboardText = json.optJSONObject("clipboard")?.optStringOrNull("text"),
                    dismissCardIds = json.optJSONObject("dismiss")
                        ?.optJSONArray("cards")?.toStringList().orEmpty(),
                    dismissNotificationIds = json.optJSONObject("dismiss")
                        ?.optJSONArray("notifications")?.toStringList().orEmpty(),
                    dismissAlertIds = json.optJSONObject("dismiss")
                        ?.optJSONArray("alerts")?.toStringList().orEmpty(),
                    confidence = parseConfidence(json.optStringOrNull("confidence")),
                    unparsed = json.optStringOrNull("unparsed"),
                    runUtterance = json.optStringOrNull("run_utterance"),
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
            // Stored as the raw JSON string. The frontend re-parses it
            // into a PresentationEnvelope via ActionHandler when the
            // user taps the Cancel button — skills can use any
            // envelope primitive they like here.
            onCancel = o.optJSONObject("on_cancel")?.toString(),
            stat = o.optJSONObject("stat")?.let { parseStat(it) },
            list = o.optJSONObject("list")?.let { parseListCard(it) },
        )
    }
    return out
}

private fun parseIconText(o: JSONObject?): IconText? {
    if (o == null) return null
    val text = o.optStringOrNull("text") ?: return null
    return IconText(icon = o.optStringOrNull("icon"), text = text)
}

private fun parseStat(o: JSONObject): Stat? {
    val headline = o.optStringOrNull("headline") ?: return null
    val metrics = o.optJSONArray("metrics")?.let { arr ->
        (0 until arr.length()).mapNotNull { parseIconText(arr.optJSONObject(it)) }
    }.orEmpty()
    return Stat(
        headline = headline,
        caption = o.optStringOrNull("caption"),
        pill = parseIconText(o.optJSONObject("pill")),
        metrics = metrics,
        background = o.optStringOrNull("background"),
        footer = parseIconText(o.optJSONObject("footer")),
    )
}

private fun parseListCard(o: JSONObject): ListCard {
    val rows = o.optJSONArray("rows")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            val leading = r.optStringOrNull("leading") ?: return@mapNotNull null
            ListRow(
                leading = leading,
                icon = r.optStringOrNull("icon"),
                text = r.optStringOrNull("text"),
                trailing = r.optStringOrNull("trailing"),
                badge = parseIconText(r.optJSONObject("badge")),
            )
        }
    }.orEmpty()
    return ListCard(
        summary = parseIconText(o.optJSONObject("summary")),
        rows = rows,
        footer = parseIconText(o.optJSONObject("footer")),
    )
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
            speak = o.optStringOrNull("speak"),
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

