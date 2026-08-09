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
    /** Names of the places currently around us, empty when we're at none. Names
     *  rather than a flag because the notification says which one. */
    val atPlaces: List<String> = emptyList(),
)

/**
 * Why the microphone is open right now, for the notification to read out. One
 * per thing currently true, so "Listening [Charging | at Home]" can tell the
 * user which of their conditions is doing the work — and, when they take the
 * charger out and Ari stays up, that the other one still holds.
 *
 * Not [ListeningCondition] reused: [AlwaysOn] is a mode rather than a
 * condition, and a place has a name where the rest have only a label.
 */
sealed interface ListeningReason {
    data object AlwaysOn : ListeningReason
    data object ScreenOn : ListeningReason
    data object Charging : ListeningReason
    data object Headset : ListeningReason
    data object Schedule : ListeningReason
    data class AtPlace(val name: String) : ListeningReason
}

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
    /** Service resident, microphone open, detector running. [reasons] is
     *  everything currently true that justifies it, never empty. */
    data class Listen(val reasons: List<ListeningReason>) : ListeningDecision

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
        ListeningMode.ALWAYS -> ListeningDecision.Listen(listOf(ListeningReason.AlwaysOn))
        ListeningMode.CUSTOM -> {
            if (conditions.isEmpty()) {
                ListeningDecision.StandBy(StandbyReason.NO_CONDITIONS)
            } else {
                val reasons = ListeningCondition.entries
                    .filter { it in conditions }
                    .flatMap { it.reasonsWhenMet(signals) }
                if (reasons.isEmpty()) ListeningDecision.StandBy(standbyReasonFor(conditions))
                else ListeningDecision.Listen(reasons)
            }
        }
    }
}

/**
 * Walked in [ListeningCondition] declaration order — the same order they're
 * listed in Settings — so the notification doesn't reshuffle itself every time
 * one comes and goes.
 */
private fun ListeningCondition.reasonsWhenMet(signals: ConditionSignals): List<ListeningReason> =
    when (this) {
        ListeningCondition.SCREEN_ON ->
            if (signals.screenOn) listOf(ListeningReason.ScreenOn) else emptyList()
        ListeningCondition.CHARGING ->
            if (signals.charging) listOf(ListeningReason.Charging) else emptyList()
        ListeningCondition.HEADSET ->
            if (signals.headsetConnected) listOf(ListeningReason.Headset) else emptyList()
        ListeningCondition.SCHEDULE ->
            if (signals.withinSchedule) listOf(ListeningReason.Schedule) else emptyList()
        ListeningCondition.PLACE -> signals.atPlaces.map(ListeningReason::AtPlace)
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
