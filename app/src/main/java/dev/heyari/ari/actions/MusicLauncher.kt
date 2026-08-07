package dev.heyari.ari.actions

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import dev.heyari.ari.media.ariListenerComponent
import dev.heyari.ari.media.hasNotificationAccess
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
    enum class Strategy { MEDIA_BROWSER, MEDIA_SESSION, PLAY_FROM_SEARCH_INTENT, SEARCH_DEEPLINK }

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
                Strategy.MEDIA_SESSION -> tryMediaSessionPlay(pkg, query)
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

    /**
     * Drive [pkg]'s live MediaSession instead of binding its browser.
     *
     * Apple Music refuses MediaBrowser connections from us — its onGetRoot
     * only admits an allowlist it isn't going to add Ari to — but the session
     * it publishes while open advertises ACTION_PLAY_FROM_SEARCH, and that we
     * can reach. Enumerating another app's sessions needs an authorised
     * notification listener, which Ari already has for transport control.
     *
     * When the app has no session yet (nothing opened it since boot) the
     * play-from-search intent brings it up — Apple Music lands on the search
     * results rather than the home screen — and its session appears shortly
     * after, so we poll briefly and then command it.
     */
    private fun tryMediaSessionPlay(pkg: String, query: String): Boolean {
        if (!hasNotificationAccess(context)) {
            Log.i(TAG, "no notification access — cannot reach $pkg's session")
            return false
        }
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        if (msm == null) {
            Log.w(TAG, "MediaSessionManager unavailable")
            return false
        }

        findController(msm, pkg)?.let { return dispatchPlayFromSearch(it, pkg, query) }

        // Nothing live yet. Open the app, then wait for its session.
        if (!tryPlayFromSearchIntent(pkg, query)) return false
        val deadline = SystemClock.uptimeMillis() + SESSION_POLL_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                Thread.sleep(SESSION_POLL_INTERVAL_MS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            findController(msm, pkg)?.let { return dispatchPlayFromSearch(it, pkg, query) }
        }
        Log.w(TAG, "$pkg published no session within ${SESSION_POLL_TIMEOUT_MS}ms of opening")
        return false
    }

    private fun findController(msm: MediaSessionManager, pkg: String): MediaController? = try {
        msm.getActiveSessions(ariListenerComponent(context))
            .firstOrNull { it.packageName == pkg }
    } catch (t: Throwable) {
        Log.w(TAG, "getActiveSessions failed", t)
        null
    }

    /**
     * Send the query to [controller] and wait for the session to actually
     * start playing.
     *
     * Advertising ACTION_PLAY_FROM_SEARCH is necessary but not sufficient: a
     * session belonging to a process that was woken headless advertises the
     * full action set and then drops the command on the floor. Nothing
     * distinguishes it beforehand — it reports PAUSED with the same mask a
     * working session does — so the only honest test is whether playback
     * starts. Returning false lets the caller fall through to a strategy that
     * at least opens the app.
     */
    private fun dispatchPlayFromSearch(
        controller: MediaController,
        pkg: String,
        query: String,
    ): Boolean {
        val actions = controller.playbackState?.actions ?: 0L
        if (actions and PlaybackState.ACTION_PLAY_FROM_SEARCH == 0L) {
            Log.w(TAG, "$pkg's session does not advertise PLAY_FROM_SEARCH (actions=$actions)")
            return false
        }
        try {
            controller.transportControls.playFromSearch(query, Bundle())
        } catch (t: Throwable) {
            Log.e(TAG, "playFromSearch failed on $pkg's session", t)
            return false
        }

        val deadline = SystemClock.uptimeMillis() + PLAYBACK_CONFIRM_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            when (controller.playbackState?.state) {
                PlaybackState.STATE_PLAYING,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING,
                -> return true
                else -> Unit
            }
            try {
                Thread.sleep(SESSION_POLL_INTERVAL_MS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        Log.w(TAG, "$pkg accepted playFromSearch but never started playing")
        return false
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
        private const val SESSION_POLL_TIMEOUT_MS = 4_000L
        private const val SESSION_POLL_INTERVAL_MS = 250L
        private const val PLAYBACK_CONFIRM_TIMEOUT_MS = 2_000L
        private const val MEDIA_BROWSER_SERVICE_ACTION = "android.media.browse.MediaBrowserService"

        // Strategy order is per-service because apps differ in which one
        // actually starts playback. Spotify honours the plain intent, so it
        // leads with the cheapest path. The rest only open a search screen on
        // the intent, so they try the browser first and then the live session
        // — Apple Music refuses the browser outright but does accept
        // playFromSearch on the session it publishes while open.
        val REGISTRY: Map<String, Service> = listOf(
            Service(
                "spotify", "Spotify", listOf("com.spotify.music"),
                listOf(Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.MEDIA_BROWSER, Strategy.SEARCH_DEEPLINK),
            ),
            // Apple Music deliberately omits MEDIA_BROWSER. It refuses the
            // connection every time (onGetRoot admits an allowlist we're not
            // on), and the attempt is not free: binding its MediaPlaybackService
            // starts the app headless, which publishes a session with no UI and
            // no audio focus. MEDIA_SESSION then finds that phantom, dispatches
            // into it, and nothing happens. Skipping the bind leaves no session
            // to find, so the intent opens the app properly instead.
            Service(
                "apple_music", "Apple Music", listOf("com.apple.android.music"),
                listOf(Strategy.MEDIA_SESSION, Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.SEARCH_DEEPLINK),
            ),
            Service(
                "tidal", "Tidal", listOf("com.aspiro.tidal"),
                listOf(Strategy.MEDIA_BROWSER, Strategy.MEDIA_SESSION, Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.SEARCH_DEEPLINK),
            ),
            Service(
                "deezer", "Deezer", listOf("deezer.android.app"),
                listOf(Strategy.MEDIA_BROWSER, Strategy.MEDIA_SESSION, Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.SEARCH_DEEPLINK),
            ),
            Service(
                "amazon_music", "Amazon Music", listOf("com.amazon.mp3"),
                listOf(Strategy.MEDIA_BROWSER, Strategy.MEDIA_SESSION, Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.SEARCH_DEEPLINK),
            ),
        ).associateBy { it.id }
    }
}
