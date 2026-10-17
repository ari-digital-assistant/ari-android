package dev.heyari.ari.actions

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.R
import dev.heyari.ari.notifications.AlertRegistry
import dev.heyari.ari.notifications.AlertSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lets the user stop a sounding alert by voice. The words must be, as a whole
 * utterance, the label of the alert's own Stop button (whatever the skill
 * chose, in whatever language) or one of [R.array.alert_stop_phrases], which
 * also carries the STT's habit of hearing "stop" as "stopped" or "stops".
 *
 * The alert takeover isn't a card, so [CardActionVoiceIntercept] never sees
 * it. Callers run this before that one and before the engine: while an alarm
 * is ringing, a bare "stop" can only mean the alarm.
 */
@Singleton
class AlertVoiceIntercept internal constructor(
    private val stopPhrases: () -> List<String>,
    private val sounding: () -> Collection<AlertSpec>,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        // Read per call, so a change of Ari's language applies at once.
        stopPhrases = { context.resources.getStringArray(R.array.alert_stop_phrases).toList() },
        sounding = AlertRegistry::sounding,
    )

    /** The id of the sounding alert [text] stops, or null to dispatch normally. */
    fun resolve(text: String): String? {
        val words = text.trim().lowercase().trimEnd('.', '!', '?', ',')
        if (words.isEmpty()) return null
        val alerts = sounding()
        if (alerts.isEmpty()) return null
        val phrases = stopPhrases()
        return alerts.firstOrNull { alert ->
            phrases.any { it.equals(words, ignoreCase = true) } ||
                alert.actions.any { it.id == STOP_ACTION_ID && it.label.equals(words, ignoreCase = true) }
        }?.id
    }

    private companion object {
        const val STOP_ACTION_ID = "stop_alert"
    }
}
