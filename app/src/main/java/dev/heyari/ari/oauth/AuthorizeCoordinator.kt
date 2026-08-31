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

    /** Set once Ari has actually gone to the background for this attempt, so
     *  resuming can tell "the user came back without finishing" apart from the
     *  moment before the browser has covered us. */
    private var leftForBrowser = false

    fun begin(timeoutMs: Long): AuthorizeHandle {
        val d = CompletableDeferred<Map<String, String>>()
        synchronized(lock) {
            pending?.completeExceptionally(CancelledAuthorize())
            pending = d
            leftForBrowser = false
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

    /** Ari went to the background — the browser is presumably up. */
    fun onBackgrounded() {
        synchronized(lock) {
            if (pending != null) leftForBrowser = true
        }
    }

    /**
     * Ari is in front again. If an authorization is still waiting and we had
     * gone away for the browser, the user came back without finishing it.
     *
     * Abandoning it matters far more than it looks. The engine call that is
     * blocked on this holds the engine-wide mutex, the same one `process_input`
     * takes — so a sign-in walked away from left Ari deaf to everything, not
     * just this button, until the skill's five-minute timeout expired. Closing
     * and reopening the app didn't help because the process, and therefore the
     * held lock, survived.
     *
     * Ordering makes this safe against the success path: the callback Activity
     * delivers and clears `pending` before we are resumed, so there is nothing
     * left here to cancel.
     */
    fun onResumed(): Boolean {
        synchronized(lock) {
            val d = pending
            if (d == null || !leftForBrowser) return false
            pending = null
            leftForBrowser = false
            return d.completeExceptionally(CancelledAuthorize())
        }
    }
}
