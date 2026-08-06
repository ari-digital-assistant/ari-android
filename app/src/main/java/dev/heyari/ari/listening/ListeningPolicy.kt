package dev.heyari.ari.listening

import androidx.annotation.StringRes
import dev.heyari.ari.R

/**
 * What the world currently looks like to the listening policy. Every field is a
 * live signal owned by a source in [ListeningController]; defaults are all
 * "condition not met" so a source that hasn't reported yet can never
 * accidentally open the mic.
 */
data class ConditionSignals(
    val screenOn: Boolean = false,
    val charging: Boolean = false,
    val headsetConnected: Boolean = false,
    val withinSchedule: Boolean = false,
    val atPlace: Boolean = false,
)

/**
 * Why Ari has gone quiet, in words the notification can use. One value per
 * single ticked condition, because "Ari will start listening when you plug in"
 * is worth saying and "conditions not met" is not.
 */
enum class StandbyReason(@StringRes val messageRes: Int) {
    NO_CONDITIONS(R.string.listening_standby_no_conditions),
    SCREEN_OFF(R.string.listening_standby_screen_off),
    NOT_CHARGING(R.string.listening_standby_not_charging),
    NO_HEADSET(R.string.listening_standby_no_headset),
    OUTSIDE_SCHEDULE(R.string.listening_standby_outside_schedule),
    AWAY_FROM_PLACES(R.string.listening_standby_away_from_places),
    MULTIPLE(R.string.listening_standby_multiple),
}

/**
 * The three genuinely different things the policy can ask for. [Off] is not
 * [StandBy] with the mic cold: it means the foreground service should not exist
 * at all, notification included.
 */
sealed interface ListeningDecision {
    /** Service resident, microphone open, detector running. */
    data object Listen : ListeningDecision

    /** Service resident, microphone released. The FGS must stay up — see below. */
    data class StandBy(val reason: StandbyReason) : ListeningDecision

    /** Service stopped outright. Only ever entered from the foreground. */
    data object Off : ListeningDecision
}

/**
 * The whole feature in one pure function.
 *
 * [StandBy] keeps the foreground service alive with no microphone. That is not
 * an optimisation, it is the only thing that works: a `microphone` FGS cannot be
 * started from the background on Android 14+, but one that is already running
 * keeps `PROCESS_CAPABILITY_FOREGROUND_MICROPHONE` for its whole life and can
 * open and close `AudioRecord` as often as it likes. A resident FGS with no mic
 * costs essentially nothing — the measured drain is the capture and the
 * inference, not the service record.
 *
 * [ListeningMode.CUSTOM] with nothing ticked stands by rather than switching
 * [Off], so [Off] keeps exactly one meaning: the user turned Ari off. It also
 * leaves the service in place to react the instant they tick something.
 *
 * There is no separate pause flag layered on top of [mode] — the top-bar
 * control sets [ListeningMode] directly, and [ListeningMode.NEVER] already
 * means "off right now" without touching the stored conditions/schedules/
 * places, so flipping back to [ListeningMode.CUSTOM] restores exactly what was
 * configured. One source of truth instead of two that can disagree.
 */
internal fun decideListening(
    mode: ListeningMode,
    conditions: Set<ListeningCondition>,
    signals: ConditionSignals,
): ListeningDecision {
    return when (mode) {
        ListeningMode.NEVER -> ListeningDecision.Off
        ListeningMode.ALWAYS -> ListeningDecision.Listen
        ListeningMode.CUSTOM -> {
            if (conditions.isEmpty()) {
                ListeningDecision.StandBy(StandbyReason.NO_CONDITIONS)
            } else if (conditions.any { it.isMet(signals) }) {
                ListeningDecision.Listen
            } else {
                ListeningDecision.StandBy(standbyReasonFor(conditions))
            }
        }
    }
}

private fun ListeningCondition.isMet(signals: ConditionSignals): Boolean = when (this) {
    ListeningCondition.SCREEN_ON -> signals.screenOn
    ListeningCondition.CHARGING -> signals.charging
    ListeningCondition.HEADSET -> signals.headsetConnected
    ListeningCondition.SCHEDULE -> signals.withinSchedule
    ListeningCondition.PLACE -> signals.atPlace
}

/**
 * Naming the one thing being waited on is useful; enumerating four of them in a
 * notification line is not.
 */
private fun standbyReasonFor(conditions: Set<ListeningCondition>): StandbyReason =
    when (conditions.singleOrNull()) {
        ListeningCondition.SCREEN_ON -> StandbyReason.SCREEN_OFF
        ListeningCondition.CHARGING -> StandbyReason.NOT_CHARGING
        ListeningCondition.HEADSET -> StandbyReason.NO_HEADSET
        ListeningCondition.SCHEDULE -> StandbyReason.OUTSIDE_SCHEDULE
        ListeningCondition.PLACE -> StandbyReason.AWAY_FROM_PLACES
        null -> StandbyReason.MULTIPLE
    }
