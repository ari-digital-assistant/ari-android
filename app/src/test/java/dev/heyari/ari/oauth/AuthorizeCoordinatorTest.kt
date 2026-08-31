package dev.heyari.ari.oauth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Abandoning a sign-in has to release the engine thread waiting on it.
 *
 * That thread holds the engine-wide mutex — the same one `process_input` takes
 * — so an authorization left hanging made Ari deaf to everything until the
 * skill's five-minute timeout, and surviving the app being closed and reopened
 * because the process, and the held lock, survived too.
 */
class AuthorizeCoordinatorTest {

    private val coordinator = AuthorizeCoordinator()

    @Test
    fun comingBackWithoutFinishingCancelsTheWait() {
        val handle = coordinator.begin(timeoutMs = 300_000)
        coordinator.onBackgrounded()
        assertTrue(coordinator.onResumed())
        assertEquals(AuthorizeOutcome.Cancelled, runBlocking { handle.await() })
    }

    @Test
    fun resumingBeforeWeEverLeftDoesNothing() {
        // The browser takes a moment to cover us. Cancelling in that window
        // would kill the sign-in the user just asked for.
        coordinator.begin(timeoutMs = 300_000)
        assertFalse(coordinator.onResumed())
    }

    @Test
    fun aDeliveredCallbackIsNotCancelledByTheResumeThatFollowsIt() {
        // The success path: the callback Activity delivers and clears the
        // pending wait before MainActivity is resumed behind it.
        val handle = coordinator.begin(timeoutMs = 300_000)
        coordinator.onBackgrounded()
        coordinator.deliver(mapOf("code" to "abc123", "state" to "s"))
        assertFalse(coordinator.onResumed())
        val outcome = runBlocking { handle.await() }
        assertEquals(AuthorizeOutcome.Success(mapOf("code" to "abc123", "state" to "s")), outcome)
    }

    @Test
    fun resumingTwiceOnlyCancelsOnce() {
        coordinator.begin(timeoutMs = 300_000)
        coordinator.onBackgrounded()
        assertTrue(coordinator.onResumed())
        assertFalse(coordinator.onResumed())
    }

    @Test
    fun nothingPendingIsNotSomethingToCancel() {
        coordinator.onBackgrounded()
        assertFalse(coordinator.onResumed())
    }

    @Test
    fun aFreshAttemptSupersedesOneLeftHanging() {
        val first = coordinator.begin(timeoutMs = 300_000)
        val second = coordinator.begin(timeoutMs = 300_000)
        assertEquals(AuthorizeOutcome.Cancelled, runBlocking { first.await() })
        coordinator.deliver(mapOf("code" to "z"))
        assertEquals(
            AuthorizeOutcome.Success(mapOf("code" to "z")),
            runBlocking { second.await() },
        )
    }

    @Test
    fun aNewAttemptClearsTheBackgroundedFlagFromTheLastOne() {
        // Otherwise the first thing that resumes after a retry cancels it.
        coordinator.begin(timeoutMs = 300_000)
        coordinator.onBackgrounded()
        coordinator.begin(timeoutMs = 300_000)
        assertFalse(coordinator.onResumed())
    }
}
