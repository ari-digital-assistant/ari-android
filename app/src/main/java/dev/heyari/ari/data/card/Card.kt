package dev.heyari.ari.data.card

import dev.heyari.ari.notifications.AlertSpec

/**
 * The Android-side mirror of a presentation `card` primitive
 * (see ari-skills/docs/action-responses.md).
 *
 * Skill-shaped data; renderer-driven (presence/absence of fields decides
 * which composable variant lights up — countdown, progress, or plain).
 *
 * `skillId` is carried alongside the card-shaped fields so the renderer can
 * resolve `asset:<path>` references in `icon` against the emitting skill's
 * bundle directory. Card primitives travel through `engine.processInput` as
 * opaque JSON; only the Android side learns the emitting skill's id from
 * the FFI envelope and stamps it onto the parsed Card.
 */
data class Card(
    val id: String,
    val skillId: String,
    val title: String,
    val subtitle: String?,
    val body: String?,
    val icon: String?,
    val countdownToTsMs: Long?,
    val startedAtTsMs: Long?,
    val progress: Float?,
    val accent: Accent,
    val actions: List<CardAction>,
    val onComplete: OnComplete?,
    /**
     * Optional envelope the frontend runs when the user taps a
     * `CardAction` with id `"cancel"` (reserved). Stored as the
     * raw JSON string the skill emitted — `ActionHandler.handle`
     * parses it through the normal presentation envelope path, so
     * any skill can use any envelope primitive here (speak, dismiss,
     * launch_app, clipboard, `run_utterance`, ...). Null if the card
     * has no cancel action.
     *
     * Generic on purpose: a skill that wants an undoable-card-action
     * just populates this field; the frontend has zero skill-specific
     * knowledge about what "cancel" should do for any given skill.
     */
    val onCancel: String?,
    val stat: Stat? = null,
    val list: ListCard? = null,
) {
    enum class Accent { DEFAULT, WARNING, SUCCESS, CRITICAL }
}

data class IconText(val icon: String?, val text: String)

data class Stat(
    val headline: String,
    val caption: String?,
    val pill: IconText?,
    val metrics: List<IconText>,
    val background: String?,
    val footer: IconText?,
)

data class ListRow(
    val leading: String,
    val icon: String?,
    val text: String?,
    val trailing: String?,
    val badge: IconText?,
)

data class ListCard(
    val summary: IconText?,
    val rows: List<ListRow>,
    val footer: IconText?,
)

data class CardAction(
    val id: String,
    val label: String,
    val utterance: String?,
    /**
     * Optional acknowledgement text the dispatcher emits directly when
     * the button has no [utterance]. Lets a skill produce a "got it"
     * bubble + TTS for a no-op button (e.g. "No" on a Yes/No card)
     * without inventing a magic-prefix utterance just to round-trip
     * back through the engine. Ignored when [utterance] is set —
     * the engine response provides the feedback in that case.
     */
    val speak: String?,
    val style: Style,
) {
    enum class Style { DEFAULT, PRIMARY, DESTRUCTIVE }
}

/**
 * Drives `CardAlarmScheduler` — when the card's countdown hits zero, fire
 * the [alert] (if any), optionally remove the card from the repo, and
 * dismiss any paired ongoing notifications by id ([dismissNotificationIds]).
 *
 * The notification dismissal is the cure for "ongoing notification ticks
 * past zero" when the card had a paired notification — the skill declares
 * the relationship via this list and the receiver acts on it without
 * needing a roundtrip back through the engine.
 */
data class OnComplete(
    val alert: AlertSpec?,
    val dismissCard: Boolean,
    val dismissNotificationIds: List<String>,
)
