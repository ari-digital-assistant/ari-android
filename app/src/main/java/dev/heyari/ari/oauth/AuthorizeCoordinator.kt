package dev.heyari.ari.oauth

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

sealed interface AuthorizeOutcome {
    data class Success(val params: Map<String, String>) : AuthorizeOutcome
    data object Timeout : AuthorizeOutcome
    data object Cancelled : AuthorizeOutcome
}

/** One in-flight authorization. `await()` blocks until delivery/timeout/cancel. */
class AuthorizeHandle internal constructor(
    private val deferred: CompletableDeferred<Map<String, String>>,
    private val timeoutMs: Long,
) {
    suspend fun await(): AuthorizeOutcome = try {
        AuthorizeOutcome.Success(withTimeout(timeoutMs) { deferred.await() })
    } catch (e: TimeoutCancellationException) {
        AuthorizeOutcome.Timeout
    } catch (e: CancelledAuthorize) {
        AuthorizeOutcome.Cancelled
    }
}

internal class CancelledAuthorize : Exception()

/**
 * Process-singleton bridging the synchronous `FfiAuthorizeProvider.authorize`
 * (a blocked engine thread) to the async App Link callback Activity. Only one
 * authorization can be pending; a new `begin` supersedes (cancels) the prior.
 */
@Singleton
class AuthorizeCoordinator @Inject constructor() {
    private val lock = Any()
    private var pending: CompletableDeferred<Map<String, String>>? = null

    fun begin(timeoutMs: Long): AuthorizeHandle {
        val d = CompletableDeferred<Map<String, String>>()
        synchronized(lock) {
            pending?.completeExceptionally(CancelledAuthorize())
            pending = d
        }
        return AuthorizeHandle(d, timeoutMs)
    }

    /** Called by the callback Activity with the redirect's query params. */
    fun deliver(params: Map<String, String>) {
        synchronized(lock) {
            pending?.complete(params)
            pending = null
        }
    }
}
