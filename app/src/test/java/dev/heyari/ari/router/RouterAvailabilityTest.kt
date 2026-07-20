package dev.heyari.ari.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the two pure decisions behind [RouterAvailability]:
 * how an HTTP status becomes a verdict, and when a cached verdict expires.
 *
 * The distinction that matters is 404 (a real answer — this locale has no
 * model) versus everything else (no answer — don't cache it, don't act on
 * it), because caching a transient 503 as "no router" would silently
 * disable routing for a day.
 */
class RouterAvailabilityTest {

    @Test
    fun httpOkMeansPublished() {
        assertEquals(true, RouterAvailability.verdictFor(200))
    }

    @Test
    fun httpNotFoundMeansUnpublished() {
        assertEquals(false, RouterAvailability.verdictFor(404))
    }

    @Test
    fun anythingElseIsNoAnswer() {
        assertNull(RouterAvailability.verdictFor(500))
        assertNull(RouterAvailability.verdictFor(503))
        assertNull(RouterAvailability.verdictFor(403))
        assertNull(RouterAvailability.verdictFor(301))
    }

    @Test
    fun verdictIsFreshWithinTtl() {
        assertTrue(RouterAvailability.isFresh(checkedAtMillis = 1_000L, nowMillis = 1_000L))
        assertTrue(
            RouterAvailability.isFresh(
                checkedAtMillis = 1_000L,
                nowMillis = 1_000L + RouterAvailability.TTL_MILLIS - 1,
            ),
        )
    }

    @Test
    fun verdictExpiresExactlyAtTtl() {
        assertFalse(
            RouterAvailability.isFresh(
                checkedAtMillis = 1_000L,
                nowMillis = 1_000L + RouterAvailability.TTL_MILLIS,
            ),
        )
    }

    @Test
    fun clockMovedBackwardsForcesReprobe() {
        // A negative age means the device clock jumped; treat the cache as
        // stale rather than trusting it until the clock catches up.
        assertFalse(RouterAvailability.isFresh(checkedAtMillis = 5_000L, nowMillis = 1_000L))
    }

    @Test
    fun ttlIsOneDay() {
        assertEquals(86_400_000L, RouterAvailability.TTL_MILLIS)
    }
}
