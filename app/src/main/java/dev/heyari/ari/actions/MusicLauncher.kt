package dev.heyari.ari.actions

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays a free-text query in a named music app, trying three dispatch
 * strategies best-first with graceful fallback:
 *
 *  1. MEDIA_BROWSER          — connect to the app's MediaBrowserService, take
 *                              its session token and call playFromSearch on the
 *                              transport controls. This is how Android Auto and
 *                              Assistant get apps to actually start playing
 *                              rather than just open a search screen.
 *  2. PLAY_FROM_SEARCH_INTENT — the classic MEDIA_PLAY_FROM_SEARCH activity
 *                              intent, scoped to the target package. Cheaper,
 *                              but an app is free to honour it by merely
 *                              opening — we can't tell from the result.
 *  3. SEARCH_DEEPLINK         — fallback: ACTION_VIEW on the app's web/app
 *                              search URL (opens results; user taps play).
 *
 * [play] blocks for up to [CONNECT_TIMEOUT_MS] on the MEDIA_BROWSER path, so
 * every caller must reach it off the main thread.
 *
 * Generic media handler — carries no skill-specific knowledge. The canonical
 * service ids match the engine's `play_media` action.
 */
@Singleton
class MusicLauncher @Inject constructor(
    private val context: Context,
) {
    enum class Strategy { MEDIA_BROWSER, PLAY_FROM_SEARCH_INTENT, SEARCH_DEEPLINK }

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
        // One line per attempt. Which strategy answered decides whether the
        // user hears music or just watches an app open, and from outside the
        // two are indistinguishable — this is the only way to tell them apart
        // after the fact.
        for (strat in svc.strategy) {
            val played = when (strat) {
                Strategy.MEDIA_BROWSER -> tryMediaBrowserPlay(pkg, query)
                Strategy.PLAY_FROM_SEARCH_INTENT -> tryPlayFromSearchIntent(pkg, query)
                Strategy.SEARCH_DEEPLINK -> {
                    val url = svc.searchUrl?.invoke(query)
                    if (url == null) {
                        Log.i(TAG, "$strat skipped for $pkg: no search URL configured")
                        false
                    } else {
                        tryDeepLink(pkg, url)
                    }
                }
            }
            Log.i(TAG, "$strat on $pkg: ${if (played) "dispatched" else "declined"}")
            if (played) {
                return if (strat == Strategy.SEARCH_DEEPLINK) {
                    PlayResult.OpenedResults(query, svc.displayName)
                } else {
                    PlayResult.Playing(query, svc.displayName)
                }
            }
        }
        return PlayResult.Failed("no strategy played on ${svc.displayName}")
    }

    /**
     * Connects to [pkg]'s MediaBrowserService, then dispatches playFromSearch
     * on the session it hands back. Returns true if we connected AND
     * dispatched within [CONNECT_TIMEOUT_MS]; the caller falls through to the
     * next strategy otherwise.
     *
     * Unlike the activity intent, this reaches the app's transport controls
     * directly, so a cooperative app starts playing instead of just opening.
     */
    private fun tryMediaBrowserPlay(pkg: String, query: String): Boolean {
        val component = browseServiceComponent(pkg)
        if (component == null) {
            Log.w(TAG, "$pkg publishes no MediaBrowserService — cannot reach its transport controls")
            return false
        }
        val connected = CountDownLatch(1)
        val dispatched = AtomicBoolean(false)
        var browser: MediaBrowser? = null

        // MediaBrowser posts its ConnectionCallback to the Handler of the
        // thread that CONSTRUCTS it. Built on the calling thread, the callbacks
        // would queue behind the latch we block on below and never fire, so the
        // strategy could only ever time out. Give the browser its own looper: a
        // dedicated HandlerThread that nothing blocks.
        val handlerThread = HandlerThread("ari-mediabrowser-$pkg").apply { start() }
        val handler = Handler(handlerThread.looper)

        val callback = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                try {
                    val b = browser
                    if (b != null && b.isConnected) {
                        MediaController(context, b.sessionToken)
                            .transportControls
                            .playFromSearch(query, Bundle())
                        dispatched.set(true)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "MediaBrowser dispatch failed for $pkg", t)
                } finally {
                    connected.countDown()
                }
            }

            override fun onConnectionSuspended() {
                connected.countDown()
            }

            override fun onConnectionFailed() {
                Log.w(TAG, "MediaBrowser connection refused by $pkg")
                connected.countDown()
            }
        }

        return try {
            // Construct + connect ON the HandlerThread so the browser binds its
            // internal Handler to that looper. Wait for construction first, so
            // `browser` is populated before any callback can read it.
            val constructed = CountDownLatch(1)
            handler.post {
                try {
                    browser = MediaBrowser(context, component, callback, null).also { it.connect() }
                } catch (t: Throwable) {
                    Log.e(TAG, "MediaBrowser connect failed for $pkg", t)
                    connected.countDown()
                } finally {
                    constructed.countDown()
                }
            }
            constructed.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            connected.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) && dispatched.get()
        } catch (t: Throwable) {
            Log.e(TAG, "MediaBrowser play failed for $pkg", t)
            false
        } finally {
            // disconnect() must run on the looper the browser was created on.
            handler.post {
                try {
                    browser?.disconnect()
                } catch (t: Throwable) {
                    Log.w(TAG, "MediaBrowser disconnect failed for $pkg", t)
                } finally {
                    handlerThread.quitSafely()
                }
            }
        }
    }

    /** Discovers [pkg]'s MediaBrowserService component, or null if it has none. */
    private fun browseServiceComponent(pkg: String): ComponentName? {
        val intent = Intent(MEDIA_BROWSER_SERVICE_ACTION).setPackage(pkg)
        val info = context.packageManager.queryIntentServices(intent, 0)
            .firstOrNull()?.serviceInfo ?: return null
        return ComponentName(info.packageName, info.name)
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
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val MEDIA_BROWSER_SERVICE_ACTION = "android.media.browse.MediaBrowserService"

        // Strategy order is per-service because apps differ in which one
        // actually starts playback. Spotify honours the plain intent, so it
        // leads with the cheaper path; the rest only open a search screen on
        // the intent and need the browser session to play.
        val REGISTRY: Map<String, Service> = listOf(
            Service(
                "spotify", "Spotify", listOf("com.spotify.music"),
                listOf(Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.MEDIA_BROWSER, Strategy.SEARCH_DEEPLINK),
            ),
            Service(
                "apple_music", "Apple Music", listOf("com.apple.android.music"),
                listOf(Strategy.MEDIA_BROWSER, Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.SEARCH_DEEPLINK),
            ),
            Service(
                "tidal", "Tidal", listOf("com.aspiro.tidal"),
                listOf(Strategy.MEDIA_BROWSER, Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.SEARCH_DEEPLINK),
            ),
            Service(
                "deezer", "Deezer", listOf("deezer.android.app"),
                listOf(Strategy.MEDIA_BROWSER, Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.SEARCH_DEEPLINK),
            ),
            Service(
                "amazon_music", "Amazon Music", listOf("com.amazon.mp3"),
                listOf(Strategy.MEDIA_BROWSER, Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.SEARCH_DEEPLINK),
            ),
        ).associateBy { it.id }
    }
}
