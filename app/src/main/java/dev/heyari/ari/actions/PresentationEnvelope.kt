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
