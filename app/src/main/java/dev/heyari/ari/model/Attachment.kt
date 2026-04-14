package dev.heyari.ari.model

/**
 * Renderable extras attached to a [Message] beyond its plain text body.
 *
 * Skill-driven UI (live countdown cards, later: maps, weather chips, etc.)
 * rides on the same bubble that produced it. The Message owns the
 * attachment list; the bubble composable dispatches on the attachment type
 * to pick the right composable underneath the text.
 */
sealed class Attachment {
    /** Renders a [TimerCard] bound to the timer with this id in [dev.heyari.ari.data.timer.TimerStateRepository]. */
    data class Timer(val timerId: String) : Attachment()
}
