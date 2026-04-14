package dev.heyari.ari.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tracks which alert ids are currently sounding. Lets [AlertActivity] (the
 * full-takeover lock-screen UI) finish itself the moment the alert stops —
 * whether the user tapped Stop, the auto-stop cap fired, or the alert was
 * cancelled by another path (e.g. the timer skill emitting `dismiss.alerts`).
 *
 * Process-local singleton (intentionally — alerts don't survive process
 * restart anyway, the foreground service holds the only authoritative
 * state). [AlertService] mutates; [AlertActivity] observes.
 */
object AlertRegistry {
    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val active: StateFlow<Set<String>> = _active.asStateFlow()

    fun start(id: String) = _active.update { it + id }
    fun stop(id: String) = _active.update { it - id }
    fun isActive(id: String): Boolean = id in _active.value
}
