package dev.heyari.ari.actions

import dev.heyari.ari.R

/**
 * A resolved spoken-feedback reference: a string resource plus an optional int
 * format arg. `resId == null` means say nothing (e.g. volume up/down — the
 * system volume slider is the feedback).
 */
data class MediaFeedback(val resId: Int?, val arg: Int? = null)

/** Pure mapping from a Done transport action to its spoken feedback resource. */
fun doneFeedback(action: String, level: Int?, mute: Boolean?): MediaFeedback = when (action) {
    "pause" -> MediaFeedback(R.string.media_paused)
    "resume" -> MediaFeedback(R.string.media_resumed)
    "next" -> MediaFeedback(R.string.media_next)
    "previous" -> MediaFeedback(R.string.media_previous)
    "stop" -> MediaFeedback(R.string.media_stopped)
    "volume" -> when {
        level != null -> MediaFeedback(R.string.media_volume_set, level)
        mute == true -> MediaFeedback(R.string.media_muted)
        mute == false -> MediaFeedback(R.string.media_unmuted)
        else -> MediaFeedback(null) // up/down: silent
    }
    else -> MediaFeedback(null)
}
