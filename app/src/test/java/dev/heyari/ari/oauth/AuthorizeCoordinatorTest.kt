package dev.heyari.ari.oauth

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthorizeCoordinatorTest {
    @Test fun deliver_completes_a_pending_await() = runBlocking {
        val c = AuthorizeCoordinator()
        val handle = c.begin(timeoutMs = 5_000)
        launch { c.deliver(mapOf("code" to "xyz", "state" to "s1")) }
        val outcome = handle.await()
        assertEquals(AuthorizeOutcome.Success(mapOf("code" to "xyz", "state" to "s1")), outcome)
    }

    @Test fun timeout_yields_timeout_outcome() = runBlocking {
        val c = AuthorizeCoordinator()
        val handle = c.begin(timeoutMs = 50)
        val outcome = handle.await() // nothing delivered
        assertEquals(AuthorizeOutcome.Timeout, outcome)
    }

    @Test fun new_begin_cancels_a_stale_pending() = runBlocking {
        val c = AuthorizeCoordinator()
        val first = c.begin(timeoutMs = 5_000)
        val second = c.begin(timeoutMs = 5_000) // supersedes
        val firstOutcome = first.await()
        assertEquals(AuthorizeOutcome.Cancelled, firstOutcome)
        launch { c.deliver(mapOf("code" to "ok")) }
        assertEquals(AuthorizeOutcome.Success(mapOf("code" to "ok")), second.await())
    }
}
