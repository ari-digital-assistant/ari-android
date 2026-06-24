package dev.heyari.ari.actions

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays a free-text query in a named music app, trying two dispatch
 * strategies best-first with graceful fallback:
 *
 *  1. PLAY_FROM_SEARCH_INTENT — the classic MEDIA_PLAY_FROM_SEARCH activity
 *                              intent, scoped to the target package. On
 *                              cooperative apps (Spotify, Apple Music, etc.)
 *                              this auto-plays without user interaction.
 *  2. SEARCH_DEEPLINK         — fallback: ACTION_VIEW on the app's web/app
 *                              search URL (opens results; user taps play).
 *
 * Generic media handler — carries no skill-specific knowledge. The canonical
 * service ids match the engine's `play_media` action.
 */
@Singleton
class MusicLauncher @Inject constructor(
    private val context: Context,
) {
    enum class Strategy { PLAY_FROM_SEARCH_INTENT, SEARCH_DEEPLINK }

    data class Service(
        val id: String,
        val displayName: String,
        val packages: List<String>,
        val strategy: List<Strategy>,
        val searchUrl: ((String) -> String)? = null,
    )

    sealed interface PlayResult {
        data class Playing(val query: String, val serviceName: String?) : PlayResult
        data class OpenedResults(val query: String, val serviceName: String) : PlayResult
        data class ServiceNotInstalled(val serviceName: String) : PlayResult
        data class NoMusicApp(val query: String) : PlayResult
        data class Failed(val reason: String) : PlayResult
    }

    /** Ids of every registered music service with at least one installed package.
     *  This is what the engine's media_services() reports back to the skill. */
    fun installedServiceIds(): List<String> =
        REGISTRY.values.filter { svc -> svc.packages.any { isInstalled(it) } }.map { it.id }

    fun play(query: String, serviceId: String?): PlayResult {
        if (query.isBlank()) return PlayResult.Failed("empty query")
        val svc = serviceId?.let { REGISTRY[it] }
            ?: return PlayResult.Failed(if (serviceId == null) "no service" else "unknown service $serviceId")
        if (svc.packages.none { isInstalled(it) }) return PlayResult.ServiceNotInstalled(svc.displayName)
        val pkg = svc.packages.first { isInstalled(it) }
        for (strat in svc.strategy) {
            when (strat) {
                Strategy.PLAY_FROM_SEARCH_INTENT -> {
                    if (tryPlayFromSearchIntent(pkg, query)) return PlayResult.Playing(query, svc.displayName)
                }
                Strategy.SEARCH_DEEPLINK -> {
                    val url = svc.searchUrl?.invoke(query)
                    if (url != null && tryDeepLink(pkg, url)) return PlayResult.OpenedResults(query, svc.displayName)
                }
            }
        }
        return PlayResult.Failed("no strategy played on ${svc.displayName}")
    }

    /**
     * Extras for playFromSearch. The documented "play from search" contract
     * wants EXTRA_MEDIA_FOCUS so the app's search can resolve a track.
     */
    private fun searchExtras(query: String): Bundle = Bundle().apply {
        putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        putString(SearchManager.QUERY, query)
    }

    /** Classic MEDIA_PLAY_FROM_SEARCH activity intent, scoped to [pkg]. */
    private fun tryPlayFromSearchIntent(pkg: String, query: String): Boolean {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(pkg)
            putExtra(SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) return false
        return startSafely(intent)
    }

    /** ACTION_VIEW on a search [url], scoped to [pkg] so it opens in-app. */
    private fun tryDeepLink(pkg: String, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) return false
        return startSafely(intent)
    }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Returns true on success, false (logged) on failure. */
    private fun startSafely(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        Log.e(TAG, "play failed", t)
        false
    }

    companion object {
        private const val TAG = "MusicLauncher"

        val REGISTRY: Map<String, Service> = listOf(
            Service(
                "spotify", "Spotify", listOf("com.spotify.music"),
                listOf(Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.SEARCH_DEEPLINK),
            ),
            Service(
                "apple_music", "Apple Music", listOf("com.apple.android.music"),
                listOf(Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.SEARCH_DEEPLINK),
            ),
            Service(
                "tidal", "Tidal", listOf("com.aspiro.tidal"),
                listOf(Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.SEARCH_DEEPLINK),
            ),
            Service(
                "deezer", "Deezer", listOf("deezer.android.app"),
                listOf(Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.SEARCH_DEEPLINK),
            ),
            Service(
                "youtube", "YouTube", listOf("com.google.android.youtube"),
                listOf(Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.SEARCH_DEEPLINK),
                searchUrl = { q -> "https://www.youtube.com/results?search_query=" + Uri.encode(q) },
            ),
            Service(
                "amazon_music", "Amazon Music", listOf("com.amazon.mp3"),
                listOf(Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.SEARCH_DEEPLINK),
            ),
        ).associateBy { it.id }
    }
}
