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
) {
    enum class Accent { DEFAULT, WARNING, SUCCESS, CRITICAL }
}

data class CardAction(
    val id: String,
    val label: String,
    val utterance: String?,
    val style: Style,
) {
    enum class Style { DEFAULT, PRIMARY, DESTRUCTIVE }
}

/**
 * Drives `CardAlarmScheduler` — when the card's countdown hits zero, fire
 * the [alert] (if any) and optionally remove the card from the repo.
 */
data class OnComplete(
    val alert: AlertSpec?,
    val dismissCard: Boolean,
)
