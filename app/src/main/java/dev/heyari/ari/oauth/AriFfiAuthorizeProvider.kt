package dev.heyari.ari.oauth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import uniffi.ari_ffi.FfiAuthorizeParam
import uniffi.ari_ffi.FfiAuthorizeProvider
import uniffi.ari_ffi.FfiAuthorizeRequest
import uniffi.ari_ffi.FfiAuthorizeResult

/**
 * Opens the authorization URL in a Custom Tab and blocks the calling (engine)
 * thread until the App Link callback delivers the redirect params, or timeout.
 * Never called on the main thread (the engine drives it from Dispatchers.IO).
 */
@Singleton
class AriFfiAuthorizeProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: AuthorizeCoordinator,
) : FfiAuthorizeProvider {

    override fun authorize(req: FfiAuthorizeRequest): FfiAuthorizeResult {
        val handle = coordinator.begin(timeoutMs = req.timeoutMs.toLong())
        return try {
            val customTabs = CustomTabsIntent.Builder().build()
            customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabs.launchUrl(context, Uri.parse(req.authUrl))
            when (val outcome = runBlocking { handle.await() }) {
                is AuthorizeOutcome.Success -> FfiAuthorizeResult(
                    ok = true,
                    params = outcome.params.map { FfiAuthorizeParam(it.key, it.value) },
                    error = null,
                )
                AuthorizeOutcome.Timeout -> FfiAuthorizeResult(false, emptyList(), "timeout")
                AuthorizeOutcome.Cancelled -> FfiAuthorizeResult(false, emptyList(), "cancelled")
            }
        } catch (t: Throwable) {
            FfiAuthorizeResult(false, emptyList(), "no_browser")
        }
    }

    override fun redirectUri(): String = OAUTH_REDIRECT_URI

    companion object {
        /** The verified App Link this app intercepts; must match AndroidManifest. */
        const val OAUTH_REDIRECT_URI = "https://heyari.dev/oauth/callback"
    }
}
