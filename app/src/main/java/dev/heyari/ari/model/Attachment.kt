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
    /**
     * Renders the card with this id from
     * [dev.heyari.ari.data.card.CardStateRepository] beneath the message
     * bubble. Card content (title, countdown, icon, actions, …) all flows
     * from the repo; the attachment just points at it by id.
     *
     * If the repo has dropped the entry by recomposition time (e.g. after
     * the deadline fired), the card composable falls back to a "Done"
     * chip so the bubble doesn't lose context.
     */
    data class Card(val cardId: String) : Attachment()
}
