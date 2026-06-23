package dev.heyari.ari.actions

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays a free-text query in a music app. No service → the OS default music
 * app via MEDIA_PLAY_FROM_SEARCH; a named service → that app's package.
 *
 * Generic media handler — carries no skill-specific knowledge. The canonical
 * service ids match the engine's `play_media` action.
 */
@Singleton
class MusicLauncher @Inject constructor(
    private val context: Context,
) {
    data class Service(
        val id: String,
        val displayName: String,
        val packages: List<String>,
    )

    sealed interface PlayResult {
        data class Playing(val query: String, val serviceName: String?) : PlayResult
        data class ServiceNotInstalled(val serviceName: String) : PlayResult
        data class NoMusicApp(val query: String) : PlayResult
        data class Failed(val reason: String) : PlayResult
    }

    /** Returns the ids of every registered music service that is currently installed.
     *  Stub — replaced in Task 14 with a real package-presence check. */
    fun installedServiceIds(): List<String> = emptyList()

    fun play(query: String, serviceId: String?): PlayResult {
        if (query.isBlank()) return PlayResult.Failed("empty query")
        if (serviceId == null) return playDefault(query)
        val svc = REGISTRY[serviceId] ?: return playDefault(query)
        val pkg = svc.packages.firstOrNull { isInstalled(it) }
            ?: return PlayResult.ServiceNotInstalled(svc.displayName)
        return playOnPackage(query, pkg, svc.displayName)
    }

    private fun playDefault(query: String): PlayResult {
        val intent = playFromSearchIntent(query)
        if (intent.resolveActivity(context.packageManager) == null) {
            return PlayResult.NoMusicApp(query)
        }
        return startSafely(intent)?.let { PlayResult.Failed(it) }
            ?: PlayResult.Playing(query, null)
    }

    private fun playOnPackage(query: String, pkg: String, displayName: String): PlayResult {
        val targeted = playFromSearchIntent(query).setPackage(pkg)
        if (targeted.resolveActivity(context.packageManager) != null) {
            return startSafely(targeted)?.let { PlayResult.Failed(it) }
                ?: PlayResult.Playing(query, displayName)
        }
        // Installed but doesn't handle PLAY_FROM_SEARCH → just open the app.
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return PlayResult.Failed("no way to play on $displayName")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startSafely(launch)?.let { PlayResult.Failed(it) }
            ?: PlayResult.Playing(query, displayName)
    }

    private fun playFromSearchIntent(query: String): Intent =
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Returns null on success, or an error message on failure. */
    private fun startSafely(intent: Intent): String? = try {
        context.startActivity(intent)
        null
    } catch (t: Throwable) {
        Log.e(TAG, "play failed", t)
        t.message ?: "unknown error"
    }

    companion object {
        private const val TAG = "MusicLauncher"

        val REGISTRY: Map<String, Service> = listOf(
            Service("spotify", "Spotify", listOf("com.spotify.music")),
            Service("apple_music", "Apple Music", listOf("com.apple.android.music")),
            Service("youtube_music", "YouTube Music", listOf("com.google.android.apps.youtube.music")),
            Service("tidal", "TIDAL", listOf("com.aspiro.tidal")),
            Service("deezer", "Deezer", listOf("deezer.android.app")),
            Service("youtube", "YouTube", listOf("com.google.android.youtube")),
            Service("amazon_music", "Amazon Music", listOf("com.amazon.mp3")),
        ).associateBy { it.id }
    }
}
