package dev.heyari.ari.oauth

import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives the verified App Link redirect (https://heyari.dev/oauth/ha/callback
 * ?code=...&state=...), hands the query params to the AuthorizeCoordinator, and
 * finishes immediately. On a correctly-verified device Android routes the
 * redirect here before the browser renders the page.
 *
 * Extends ComponentActivity (the same base as MainActivity) so Hilt's
 * @AndroidEntryPoint injection works cleanly.
 */
@AndroidEntryPoint
class OAuthCallbackActivity : ComponentActivity() {
    @Inject lateinit var coordinator: AuthorizeCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val params = buildMap {
            uri?.queryParameterNames?.forEach { name ->
                uri.getQueryParameter(name)?.let { put(name, it) }
            }
        }
        coordinator.deliver(params)
        finish()
    }
}
