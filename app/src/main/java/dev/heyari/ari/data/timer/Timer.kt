package dev.heyari.ari.data.timer

/**
 * Android-side mirror of a timer tracked by the `dev.heyari.timer` skill.
 *
 * The skill is authoritative for the set of timers; Android mirrors this list
 * into [TimerStateRepository] so UI and notifications have live state without
 * having to re-ask the skill every second. The skill includes a full `timers`
 * snapshot in every action response, which we use to reconcile drift.
 */
data class Timer(
    val id: String,
    val name: String?,
    val endTsMs: Long,
    val createdTsMs: Long,
)
