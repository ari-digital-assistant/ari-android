package dev.heyari.ari.messaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeNudgeTest {

    private val now = 1_700_000_000_000L

    @Test
    fun anOfferNeverMadeIsDue() {
        assertTrue(HandsFreeNudge.isDue(null, now))
    }

    @Test
    fun anOfferJustMadeIsNotDue() {
        assertFalse(HandsFreeNudge.isDue(now, now))
    }

    @Test
    fun theOfferComesBackAfterExactlySixHours() {
        // Boundary is inclusive: at the six-hour mark the offer is due again.
        val sixHoursAgo = now - HandsFreeNudge.THROTTLE_MILLIS
        assertTrue(HandsFreeNudge.isDue(sixHoursAgo, now))
    }

    @Test
    fun oneMillisecondShortOfSixHoursStaysQuiet() {
        val almost = now - HandsFreeNudge.THROTTLE_MILLIS + 1
        assertFalse(HandsFreeNudge.isDue(almost, now))
    }

    @Test
    fun theIntervalIsSixHours() {
        assertTrue(HandsFreeNudge.THROTTLE_MILLIS == 6L * 60 * 60 * 1000)
    }

    @Test
    fun aClockThatWentBackwardsStaysQuiet() {
        // A user changing timezone must not unlock a second offer.
        assertFalse(HandsFreeNudge.isDue(now + 1, now))
    }
}
