package dev.heyari.ari.actions

import android.util.Log
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses JSON action payloads from the engine and dispatches them to the
 * appropriate Android-side handler. Returns a human-friendly text response
 * suitable for display + TTS.
 */
@Singleton
class ActionHandler @Inject constructor(
    private val appLauncher: AppLauncher,
    private val webSearchLauncher: WebSearchLauncher,
) {

    fun handle(json: String): String {
        val obj = try {
            JSONObject(json)
        } catch (t: Throwable) {
            Log.e(TAG, "Invalid action JSON: $json", t)
            return "I couldn't understand that action."
        }

        return when (val action = obj.optString("action")) {
            "open" -> handleOpen(obj.optString("target"))
            "search" -> handleSearch(obj.optString("query"))
            else -> {
                Log.w(TAG, "Unknown action type: $action")
                "I don't know how to do that yet."
            }
        }
    }

    private fun handleOpen(target: String): String {
        if (target.isBlank()) return "What would you like me to open?"

        return when (val result = appLauncher.launch(target)) {
            is AppLauncher.LaunchResult.Launched ->
                "Opening ${result.app.label}."
            is AppLauncher.LaunchResult.NotFound ->
                "I couldn't find an app called ${result.target}."
            is AppLauncher.LaunchResult.Failed ->
                "I couldn't open ${result.app.label}: ${result.reason}."
        }
    }

    private fun handleSearch(query: String): String {
        if (query.isBlank()) return "What would you like me to search for?"

        return when (val result = webSearchLauncher.search(query)) {
            is WebSearchLauncher.SearchResult.Launched ->
                "Searching for ${result.query}."
            is WebSearchLauncher.SearchResult.Failed ->
                "I couldn't search: ${result.reason}."
        }
    }

    companion object {
        private const val TAG = "ActionHandler"
    }
}
