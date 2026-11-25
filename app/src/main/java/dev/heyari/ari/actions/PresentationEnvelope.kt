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
import org.json.JSONObject
import uniffi.ari_ffi.FfiAccent
import uniffi.ari_ffi.FfiAlertSpec
import uniffi.ari_ffi.FfiButtonStyle
import uniffi.ari_ffi.FfiCard
import uniffi.ari_ffi.FfiConfidence
import uniffi.ari_ffi.FfiIconText
import uniffi.ari_ffi.FfiImportance
import uniffi.ari_ffi.FfiNotificationSpec
import uniffi.ari_ffi.FfiPresentationEnvelope
import uniffi.ari_ffi.FfiUrgency
import uniffi.ari_ffi.parsePresentationEnvelope

/** A media action from the engine, e.g. play a [query] optionally on a named [service]. */
data class MediaAction(
    val action: String,
    val query: String?,
    val service: String?,
    val direction: String? = null,
    val level: Int? = null,
    val mute: Boolean? = null,
)

/**
 * A navigation action from the engine. `mode` is "default_app" (or null) / "turn_by_turn".
 * [place] is "home" when the destination is the user's saved home, else null.
 */
data class NavigateAction(
    val destination: String,
    val mode: String?,
    val place: String? = null,
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

/**
 * A store skill the engine offers because it would handle what the user
 * asked and isn't installed (ari-android#44). The chat shows a button that
 * opens its page in Skills.
 */
data class SkillSuggestion(
    val id: String,
    val name: String,
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
 * The engine parses it (`ari_core::envelope`), with its defaults and its
 * leniency; this is the engine's result in the app's own types. The wire
 * format is documented in
 * [docs/reference-actions.md](https://github.com/ari-digital-assistant/ari-skills/blob/main/docs/reference-actions.md).
 * `skillId` is supplied by the FFI layer (it's not in the envelope itself);
 * the engine stamps it onto every primitive that needs to resolve assets.
 *
 * [parse] returns null for an envelope version the engine doesn't read, or
 * anything that isn't an envelope. Callers fall back to "I couldn't
 * understand that action."
 */
data class PresentationEnvelope(
    val speak: String?,
    /**
     * Bubble text, when the skill wants it to differ from what is spoken.
     * Null means the bubble shows [speak].
     */
    val display: String?,
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
    /** How sure the emitting skill is about its own output: HIGH when it didn't say. */
    val confidence: ParseConfidence,
    /** The residue phrase the skill noticed but couldn't consume. Null when [confidence] is HIGH. */
    val unparsed: String?,
    /** A store skill to offer, from the engine's `suggest_skill`. */
    val suggestSkill: SkillSuggestion?,
    /** Whether it puts anything on screen or takes anything off it, as the engine decides. */
    val hasPresentationPrimitives: Boolean,
) {
    enum class ParseConfidence { HIGH, PARTIAL, LOW }

    companion object {
        fun parse(json: JSONObject, skillId: String): PresentationEnvelope? {
            val parsed = parsePresentationEnvelope(json.toString(), skillId)
            if (parsed == null) Log.w(TAG, "envelope rejected: not one this engine reads")
            return parsed?.toEnvelope()
        }

        private const val TAG = "PresentationEnvelope"
    }
}

// -- The engine's types as the app's ------------------------------------

private fun FfiPresentationEnvelope.toEnvelope() = PresentationEnvelope(
    speak = speak,
    display = display,
    cards = cards.map { it.toCard() },
    alerts = alerts.map { it.toAlert() },
    notifications = notifications.map { it.toNotification() },
    launchApp = launchApp,
    media = media?.let {
        MediaAction(it.action, it.query, it.service, it.direction, it.level?.toInt(), it.mute)
    },
    alarm = alarm?.let {
        AlarmAction(it.op, it.hour?.toInt(), it.minute?.toInt(), it.message, it.days, it.skipUi)
    },
    navigate = navigate?.let { NavigateAction(it.destination, it.mode, it.place) },
    message = message?.let {
        MessageAction(it.text, it.service, it.recipientId, it.recipientLabel, it.delivery)
    },
    reply = reply?.let { ReplyAction(it.text, it.recipientLabel) },
    search = search,
    openUrl = openUrl,
    clipboardText = clipboardText,
    dismissCardIds = dismissCards,
    dismissNotificationIds = dismissNotifications,
    dismissAlertIds = dismissAlerts,
    runUtterance = runUtterance,
    confidence = when (confidence) {
        FfiConfidence.HIGH -> PresentationEnvelope.ParseConfidence.HIGH
        FfiConfidence.PARTIAL -> PresentationEnvelope.ParseConfidence.PARTIAL
        FfiConfidence.LOW -> PresentationEnvelope.ParseConfidence.LOW
    },
    unparsed = unparsed,
    suggestSkill = suggestSkill?.let { SkillSuggestion(it.id, it.name) },
    hasPresentationPrimitives = hasPresentation,
)

private fun FfiCard.toCard() = Card(
    id = id,
    skillId = skillId,
    title = title,
    subtitle = subtitle,
    body = body,
    icon = icon,
    countdownToTsMs = countdownToTsMs,
    startedAtTsMs = startedAtTsMs,
    progress = progress,
    accent = when (accent) {
        FfiAccent.DEFAULT -> Card.Accent.DEFAULT
        FfiAccent.WARNING -> Card.Accent.WARNING
        FfiAccent.SUCCESS -> Card.Accent.SUCCESS
        FfiAccent.CRITICAL -> Card.Accent.CRITICAL
    },
    actions = actions.map {
        CardAction(it.id, it.label, it.utterance, it.speak, it.style.toCardStyle())
    },
    onComplete = onComplete?.let {
        OnComplete(it.alert?.toAlert(), it.dismissCard, it.dismissNotifications)
    },
    onCancel = onCancel,
    stat = stat?.let {
        Stat(
            headline = it.headline,
            caption = it.caption,
            pill = it.pill?.toIconText(),
            metrics = it.metrics.map { m -> m.toIconText() },
            background = it.background,
            footer = it.footer?.toIconText(),
        )
    },
    list = list?.let {
        ListCard(
            summary = it.summary?.toIconText(),
            rows = it.rows.map { r -> ListRow(r.leading, r.icon, r.text, r.trailing, r.badge?.toIconText()) },
            footer = it.footer?.toIconText(),
        )
    },
)

private fun FfiIconText.toIconText() = IconText(icon = icon, text = text)

private fun FfiButtonStyle.toCardStyle() = when (this) {
    FfiButtonStyle.DEFAULT -> CardAction.Style.DEFAULT
    FfiButtonStyle.PRIMARY -> CardAction.Style.PRIMARY
    FfiButtonStyle.DESTRUCTIVE -> CardAction.Style.DESTRUCTIVE
}

private fun FfiButtonStyle.toAlertStyle() = when (this) {
    FfiButtonStyle.DEFAULT -> AlertAction.Style.DEFAULT
    FfiButtonStyle.PRIMARY -> AlertAction.Style.PRIMARY
    FfiButtonStyle.DESTRUCTIVE -> AlertAction.Style.DESTRUCTIVE
}

private fun FfiAlertSpec.toAlert() = AlertSpec(
    id = id,
    skillId = skillId,
    title = title,
    body = body,
    urgency = when (urgency) {
        FfiUrgency.NORMAL -> AlertSpec.Urgency.NORMAL
        FfiUrgency.HIGH -> AlertSpec.Urgency.HIGH
        FfiUrgency.CRITICAL -> AlertSpec.Urgency.CRITICAL
    },
    sound = sound,
    speechLoop = speechLoop,
    autoStopMs = autoStopMs,
    maxCycles = maxCycles.toInt(),
    fullTakeover = fullTakeover,
    actions = actions.map { AlertAction(it.id, it.label, it.utterance, it.style.toAlertStyle()) },
    icon = icon,
)

private fun FfiNotificationSpec.toNotification() = NotificationPrimitive(
    id = id,
    skillId = skillId,
    title = title,
    body = body,
    importance = when (importance) {
        FfiImportance.MIN -> NotificationPrimitive.Importance.MIN
        FfiImportance.LOW -> NotificationPrimitive.Importance.LOW
        FfiImportance.DEFAULT -> NotificationPrimitive.Importance.DEFAULT
        FfiImportance.HIGH -> NotificationPrimitive.Importance.HIGH
    },
    sticky = sticky,
    countdownToTsMs = countdownToTsMs,
    actions = actions.map { NotificationAction(it.id, it.label, it.utterance) },
)
