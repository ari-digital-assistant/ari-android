package dev.heyari.ari.listening

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the user's listening preferences plus the state of the world into a
 * stream of [ListeningDecision]s for [dev.heyari.ari.wakeword.WakeWordService]
 * to act on.
 *
 * Nothing here is registered eagerly. Every source hangs off the returned flow,
 * so a user on Always or Never mode pays for no receivers, no alarms and no
 * geofences at all — and a Custom user pays only for the conditions they ticked.
 */
@Singleton
class ListeningController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val scheduleAlarms: ScheduleAlarms,
    private val placeGeofences: PlaceGeofences,
) {
    private data class Config(
        val mode: ListeningMode,
        val conditions: Set<ListeningCondition>,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val decisions: Flow<ListeningDecision> = combine(
        settingsRepository.listeningMode,
        settingsRepository.listeningConditions,
        ::Config,
    )
        .distinctUntilChanged()
        .flatMapLatest { config ->
            // Always and Never are decided by the mode alone. Don't stand up a
            // single receiver to reach a conclusion we already have.
            if (config.mode != ListeningMode.CUSTOM) {
                flowOf(decideListening(config.mode, config.conditions, ConditionSignals()))
            } else {
                signalsFor(config.conditions).map { signals ->
                    decideListening(config.mode, config.conditions, signals)
                }
            }
        }
        .distinctUntilChanged()

    private fun signalsFor(conditions: Set<ListeningCondition>): Flow<ConditionSignals> {
        fun gated(condition: ListeningCondition, source: () -> Flow<Boolean>): Flow<Boolean> =
            if (condition in conditions) source() else flowOf(false)

        val places =
            if (ListeningCondition.PLACE in conditions) placeSignal() else flowOf(emptyList())

        return combine(
            gated(ListeningCondition.SCREEN_ON) { screenOnFlow(context) },
            gated(ListeningCondition.CHARGING) { chargingFlow(context) },
            gated(ListeningCondition.HEADSET) { headsetFlow(context) },
            gated(ListeningCondition.SCHEDULE) { scheduleSignal() },
            places,
        ) { screenOn, charging, headset, withinSchedule, atPlaces ->
            ConditionSignals(screenOn, charging, headset, withinSchedule, atPlaces)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun scheduleSignal(): Flow<Boolean> =
        settingsRepository.listeningSchedules.flatMapLatest { schedules ->
            scheduleWindowFlow(context, schedules, scheduleAlarms)
        }

    /**
     * Geofences are registered on subscribe and torn down on cancel. There is no
     * point holding them while the service is down: a geofence transition cannot
     * start a microphone foreground service from the background anyway, so one
     * that outlived its collector would burn location power to deliver an event
     * nothing could act on.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun placeSignal(): Flow<List<String>> =
        settingsRepository.listeningPlaces.flatMapLatest { places ->
            placeGeofences.insidePlaceNames
                .onStart { placeGeofences.register(places) }
                .onCompletion { placeGeofences.clear() }
        }
}
