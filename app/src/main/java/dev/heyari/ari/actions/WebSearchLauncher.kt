package dev.heyari.ari.actions

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSearchLauncher @Inject constructor(
    private val context: Context,
) {

    sealed interface SearchResult {
        data class Launched(val query: String) : SearchResult
        data class Failed(val reason: String) : SearchResult
    }

    fun search(query: String): SearchResult {
        if (query.isBlank()) return SearchResult.Failed("empty query")

        // Try the standard WEB_SEARCH intent first — handled by Chrome, Firefox,
        // most browsers, and dedicated search apps.
        val webSearch = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(webSearch)
            Log.i(TAG, "Launched web search for: $query")
            SearchResult.Launched(query)
        } catch (e: ActivityNotFoundException) {
            // Fall back to opening a Google search URL in the default browser
            tryBrowserFallback(query)
        } catch (t: Throwable) {
            Log.e(TAG, "Web search failed", t)
            SearchResult.Failed(t.message ?: "unknown error")
        }
    }

    private fun tryBrowserFallback(query: String): SearchResult {
        val url = "https://www.google.com/search?q=${Uri.encode(query)}"
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(viewIntent)
            Log.i(TAG, "Launched browser fallback for: $query")
            SearchResult.Launched(query)
        } catch (t: Throwable) {
            Log.e(TAG, "Browser fallback failed", t)
            SearchResult.Failed("no browser or search app available")
        }
    }

    companion object {
        private const val TAG = "WebSearchLauncher"
    }
}
