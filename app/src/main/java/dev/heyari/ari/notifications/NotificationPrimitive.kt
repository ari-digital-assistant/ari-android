package dev.heyari.ari.notifications

/**
 * Skill-declared persistent notification — the `notification` primitive
 * from ari-skills/docs/action-responses.md. Distinct from [AlertSpec]:
 * an alert grabs attention NOW; a notification sits in the shade.
 */
data class NotificationPrimitive(
    val id: String,
    val skillId: String,
    val title: String,
    val body: String?,
    val importance: Importance,
    val sticky: Boolean,
    val countdownToTsMs: Long?,
    val actions: List<NotificationAction>,
) {
    enum class Importance { MIN, LOW, DEFAULT, HIGH }
}

data class NotificationAction(
    val id: String,
    val label: String,
    val utterance: String?,
)
