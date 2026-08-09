package dev.heyari.ari.listening

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The listening decision. [decideListening] reads no Android state, so unlike
 * the controller that feeds it, it can be tested exactly — which matters,
 * because getting it wrong either drains the battery this feature exists to save
 * or leaves Ari deaf.
 */
class ListeningPolicyTest {

    private val allSignals = ConditionSignals(
        screenOn = true,
        charging = true,
        headsetConnected = true,
        withinSchedule = true,
        atPlaces = listOf("Home"),
    )

    private fun decide(
        mode: ListeningMode,
        conditions: Set<ListeningCondition> = emptySet(),
        signals: ConditionSignals = ConditionSignals(),
    ) = decideListening(mode, conditions, signals)

    @Test
    fun `always listens`() {
        assertEquals(
            ListeningDecision.Listen(listOf(ListeningReason.AlwaysOn)),
            decide(ListeningMode.ALWAYS),
        )
    }

    @Test
    fun `never is off, not standby`() {
        assertEquals(ListeningDecision.Off, decide(ListeningMode.NEVER))
    }

    @Test
    fun `custom with nothing ticked stands by rather than switching off`() {
        assertEquals(
            ListeningDecision.StandBy(StandbyReason.NO_CONDITIONS),
            decide(ListeningMode.CUSTOM, signals = allSignals),
        )
    }

    @Test
    fun `a signal only counts when its condition is ticked`() {
        assertEquals(
            ListeningDecision.StandBy(StandbyReason.NOT_CHARGING),
            decide(
                ListeningMode.CUSTOM,
                conditions = setOf(ListeningCondition.CHARGING),
                signals = ConditionSignals(
                    screenOn = true,
                    headsetConnected = true,
                    atPlaces = listOf("Home"),
                ),
            ),
        )
    }

    @Test
    fun `each condition opens the mic on its own signal, and names itself`() {
        val cases = mapOf(
            ListeningCondition.SCREEN_ON to
                (ConditionSignals(screenOn = true) to ListeningReason.ScreenOn),
            ListeningCondition.CHARGING to
                (ConditionSignals(charging = true) to ListeningReason.Charging),
            ListeningCondition.HEADSET to
                (ConditionSignals(headsetConnected = true) to ListeningReason.Headset),
            ListeningCondition.SCHEDULE to
                (ConditionSignals(withinSchedule = true) to ListeningReason.Schedule),
            ListeningCondition.PLACE to
                (ConditionSignals(atPlaces = listOf("Home")) to ListeningReason.AtPlace("Home")),
        )
        cases.forEach { (condition, case) ->
            val (signals, reason) = case
            assertEquals(
                "$condition should listen on its own signal",
                ListeningDecision.Listen(listOf(reason)),
                decide(ListeningMode.CUSTOM, conditions = setOf(condition), signals = signals),
            )
        }
    }

    @Test
    fun `conditions are ORed, so one of several is enough`() {
        assertEquals(
            ListeningDecision.Listen(listOf(ListeningReason.Charging)),
            decide(
                ListeningMode.CUSTOM,
                conditions = setOf(
                    ListeningCondition.SCREEN_ON,
                    ListeningCondition.CHARGING,
                    ListeningCondition.PLACE,
                ),
                signals = ConditionSignals(charging = true),
            ),
        )
    }

    @Test
    fun `every met condition is reported, in the order they are listed`() {
        assertEquals(
            ListeningDecision.Listen(
                listOf(
                    ListeningReason.ScreenOn,
                    ListeningReason.Charging,
                    ListeningReason.AtPlace("Home"),
                )
            ),
            decide(
                ListeningMode.CUSTOM,
                conditions = setOf(
                    ListeningCondition.PLACE,
                    ListeningCondition.CHARGING,
                    ListeningCondition.SCREEN_ON,
                ),
                signals = ConditionSignals(
                    screenOn = true,
                    charging = true,
                    atPlaces = listOf("Home"),
                ),
            ),
        )
    }

    @Test
    fun `an unticked condition never reaches the notification, however true it is`() {
        assertEquals(
            ListeningDecision.Listen(listOf(ListeningReason.Charging)),
            decide(
                ListeningMode.CUSTOM,
                conditions = setOf(ListeningCondition.CHARGING),
                signals = allSignals,
            ),
        )
    }

    @Test
    fun `overlapping places are each named`() {
        assertEquals(
            ListeningDecision.Listen(
                listOf(ListeningReason.AtPlace("Home"), ListeningReason.AtPlace("The shed"))
            ),
            decide(
                ListeningMode.CUSTOM,
                conditions = setOf(ListeningCondition.PLACE),
                signals = ConditionSignals(atPlaces = listOf("Home", "The shed")),
            ),
        )
    }

    @Test
    fun `a single unmet condition names itself`() {
        val expected = mapOf(
            ListeningCondition.SCREEN_ON to StandbyReason.SCREEN_OFF,
            ListeningCondition.CHARGING to StandbyReason.NOT_CHARGING,
            ListeningCondition.HEADSET to StandbyReason.NO_HEADSET,
            ListeningCondition.SCHEDULE to StandbyReason.OUTSIDE_SCHEDULE,
            ListeningCondition.PLACE to StandbyReason.AWAY_FROM_PLACES,
        )
        expected.forEach { (condition, reason) ->
            assertEquals(
                "$condition should report $reason",
                ListeningDecision.StandBy(reason),
                decide(ListeningMode.CUSTOM, conditions = setOf(condition)),
            )
        }
    }

    @Test
    fun `several unmet conditions report generically`() {
        assertEquals(
            ListeningDecision.StandBy(StandbyReason.MULTIPLE),
            decide(
                ListeningMode.CUSTOM,
                conditions = setOf(ListeningCondition.CHARGING, ListeningCondition.HEADSET),
            ),
        )
    }

    @Test
    fun `ticked conditions are ignored outside custom mode`() {
        assertEquals(
            ListeningDecision.Off,
            decide(
                ListeningMode.NEVER,
                conditions = setOf(ListeningCondition.SCREEN_ON),
                signals = allSignals,
            ),
        )
    }

    @Test
    fun `an unknown mode slug falls back to always, never to silence`() {
        assertEquals(ListeningMode.ALWAYS, ListeningMode.fromSlug("banana"))
        assertEquals(ListeningMode.ALWAYS, ListeningMode.fromSlug(null))
        assertEquals(ListeningMode.CUSTOM, ListeningMode.fromSlug("custom"))
    }
}
