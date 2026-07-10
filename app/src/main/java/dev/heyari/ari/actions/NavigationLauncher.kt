package dev.heyari.ari.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Honours the engine's `navigate` action by handing off to the platform maps
 * app via an `ACTION_VIEW` intent. `mode == "turn_by_turn"` starts Google Maps
 * turn-by-turn (falling back to the vendor-neutral `geo:` view if no app
 * handles the scheme); any other mode opens the destination in the user's
 * default maps app via `geo:`.
 */
@Singleton
class NavigationLauncher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    sealed interface LaunchResult {
        data object Launched : LaunchResult
        data object NoMapsApp : LaunchResult
    }

    fun launch(action: NavigateAction): LaunchResult {
        val encoded = Uri.encode(action.destination)
        for (uri in navUris(encoded, action.mode)) {
            if (startView(uri)) return LaunchResult.Launched
        }
        return LaunchResult.NoMapsApp
    }

    private fun startView(uri: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no app handled $uri", e)
        false
    }

    companion object {
        private const val TAG = "NavigationLauncher"

        /**
         * Ordered ACTION_VIEW URIs to try, most-preferred first. [encoded] is the
         * already-URL-encoded destination. `turn_by_turn` starts Google Maps
         * turn-by-turn, falling back to the vendor-neutral `geo:` view if no app
         * handles it; anything else (incl. null) uses `geo:` only.
         */
        fun navUris(encoded: String, mode: String?): List<String> =
            if (mode == "turn_by_turn") {
                listOf("google.navigation:q=$encoded", "geo:0,0?q=$encoded")
            } else {
                listOf("geo:0,0?q=$encoded")
            }
    }
}
