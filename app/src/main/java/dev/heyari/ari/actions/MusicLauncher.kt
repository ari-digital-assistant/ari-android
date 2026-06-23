package dev.heyari.ari.actions

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.util.Log
import dev.heyari.ari.media.AriMediaNotificationListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays a free-text query in a named music app, trying several dispatch
 * strategies best-first with graceful fallback:
 *
 *  1. MEDIA_BROWSER          — connect to the app's MediaBrowserService, grab
 *                              its session token and call playFromSearch on the
 *                              transport controls. This is how Android Auto /
 *                              Assistant get apps like YouTube Music to actually
 *                              auto-play rather than just open a search screen.
 *  2. PLAY_FROM_SEARCH_INTENT — the classic MEDIA_PLAY_FROM_SEARCH activity
 *                              intent, scoped to the target package.
 *  3. SEARCH_DEEPLINK         — last resort: ACTION_VIEW on the app's web/app
 *                              search URL (opens results; user taps play).
 *
 * Generic media handler — carries no skill-specific knowledge. The canonical
 * service ids match the engine's `play_media` action.
 */
@Singleton
class MusicLauncher @Inject constructor(
    private val context: Context,
) {
    enum class Strategy { MEDIA_SESSION, MEDIA_BROWSER, PLAY_FROM_SEARCH_INTENT, SEARCH_DEEPLINK }

    data class Service(
        val id: String,
        val displayName: String,
        val packages: List<String>,
        val strategy: List<Strategy>,
        val searchUrl: ((String) -> String)? = null,
    )

    sealed interface PlayResult {
        data class Playing(val query: String, val serviceName: String?) : PlayResult
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
        val pkg = svc.packages.firstOrNull { isInstalled(it) }
            ?: return PlayResult.ServiceNotInstalled(svc.displayName)
        for (strat in svc.strategy) {
            val ok = when (strat) {
                Strategy.MEDIA_SESSION -> tryMediaSessionPlay(pkg, query)
                Strategy.MEDIA_BROWSER -> tryMediaBrowserPlay(pkg, query)
                Strategy.PLAY_FROM_SEARCH_INTENT -> tryPlayFromSearchIntent(pkg, query)
                Strategy.SEARCH_DEEPLINK -> svc.searchUrl?.let { tryDeepLink(pkg, it(query)) } ?: false
            }
            if (ok) return PlayResult.Playing(query, svc.displayName)
        }
        return PlayResult.Failed("no strategy played on ${svc.displayName}")
    }

    /**
     * SPIKE strategy: drive [pkg]'s ALREADY-LIVE MediaSession.
     *
     * Some apps (notably YouTube Music) signature-gate their MediaBrowserService
     * so MEDIA_BROWSER is a dead end for us, yet their active MediaSession still
     * advertises ACTION_PLAY_FROM_SEARCH. So instead of binding the browser we
     * enumerate the system's active sessions, find the controller belonging to
     * [pkg] and dispatch playFromSearch straight onto its transport controls.
     *
     * Enumerating other apps' sessions needs one of:
     *   - the MEDIA_CONTENT_CONTROL permission (system/privileged only), OR
     *   - a notification-listener component the user has authorised, passed to
     *     getActiveSessions(ComponentName).
     * Being the default assistant *may* also be enough for getActiveSessions(null)
     * — this spike tries both and logs which path actually works.
     *
     * Heavy MEDIASPIKE: logging throughout so the device run is greppable.
     */
    private fun tryMediaSessionPlay(pkg: String, query: String): Boolean {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        if (msm == null) {
            Log.w(TAG, "MEDIASPIKE: MediaSessionManager unavailable")
            return false
        }

        // First pass: maybe the session is already live (app already playing /
        // foregrounded). If so we can dispatch immediately, no launch needed.
        findController(msm, pkg)?.let { controller ->
            return dispatch(controller, pkg, query)
        }

        // No live session for pkg yet. Launch the app, then poll for its session
        // to appear. We're off the main thread (play() runs on Dispatchers.Default),
        // so a bounded sleep-poll loop is acceptable here.
        Log.i(TAG, "MEDIASPIKE: no active session for $pkg yet, launching app then polling")
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Log.w(TAG, "MEDIASPIKE: no launch intent for $pkg, cannot bring up a session")
            return false
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!startSafely(launch)) {
            Log.w(TAG, "MEDIASPIKE: launch of $pkg failed")
            return false
        }

        val deadline = SystemClock.uptimeMillis() + SESSION_POLL_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                Thread.sleep(SESSION_POLL_INTERVAL_MS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "MEDIASPIKE: poll interrupted for $pkg")
                return false
            }
            val controller = findController(msm, pkg)
            if (controller != null) {
                Log.i(TAG, "MEDIASPIKE: session for $pkg appeared after launch+poll")
                return dispatch(controller, pkg, query)
            }
        }
        Log.w(TAG, "MEDIASPIKE: session for $pkg never appeared within ${SESSION_POLL_TIMEOUT_MS}ms")
        return false
    }

    /**
     * Enumerates active sessions and returns the controller for [pkg], or null.
     * Tries getActiveSessions(null) first (works if the assistant role alone
     * suffices), then falls back to passing our authorised notification-listener
     * component. Logs exactly which path worked so the spike can conclude.
     */
    private fun findController(msm: MediaSessionManager, pkg: String): MediaController? {
        var sessions: List<MediaController>? = null

        // (a) getActiveSessions(null) — needs MEDIA_CONTENT_CONTROL or, on some
        //     builds, the enabled notification-listener / assistant privilege.
        try {
            sessions = msm.getActiveSessions(null)
            Log.i(TAG, "MEDIASPIKE: getActiveSessions(null) ok, ${sessions.size} sessions")
        } catch (se: SecurityException) {
            Log.w(TAG, "MEDIASPIKE: getActiveSessions(null) SecurityException: ${se.message}")
        }

        // (b) Fallback: pass our notification-listener component. Only valid if
        //     the user has granted notification access to it.
        if (sessions == null) {
            val component = ComponentName(context, AriMediaNotificationListener::class.java)
            try {
                sessions = msm.getActiveSessions(component)
                Log.i(TAG, "MEDIASPIKE: getActiveSessions($component) ok, ${sessions.size} sessions")
            } catch (se: SecurityException) {
                Log.w(TAG, "MEDIASPIKE: getActiveSessions($component) SecurityException: ${se.message}")
            }
        }

        if (sessions == null) {
            Log.w(TAG, "MEDIASPIKE: no session access by either path")
            return null
        }

        val match = sessions.firstOrNull { it.packageName == pkg }
        if (match == null) {
            val pkgs = sessions.joinToString(", ") { it.packageName }
            Log.i(TAG, "MEDIASPIKE: no controller for $pkg (live packages: [$pkgs])")
        } else {
            val actions = match.playbackState?.actions
            Log.i(TAG, "MEDIASPIKE: controller found for $pkg, playbackState.actions=$actions")
        }
        return match
    }

    /** Dispatches playFromSearch onto [controller]. Returns true on dispatch. */
    private fun dispatch(controller: MediaController, pkg: String, query: String): Boolean {
        return try {
            Log.i(TAG, "MEDIASPIKE: dispatching playFromSearch(\"$query\") to $pkg")
            controller.transportControls.playFromSearch(query, Bundle())
            true
        } catch (t: Throwable) {
            Log.e(TAG, "MEDIASPIKE: playFromSearch dispatch failed for $pkg", t)
            false
        }
    }

    /**
     * Connects to [pkg]'s MediaBrowserService, waits (briefly) for the
     * connection, then dispatches playFromSearch on the resulting media
     * session. Returns true if we connected and dispatched within the timeout,
     * false otherwise — the caller falls through to the next strategy.
     */
    private fun tryMediaBrowserPlay(pkg: String, query: String): Boolean {
        val component = browseServiceComponent(pkg) ?: return false
        val latch = CountDownLatch(1)
        val dispatched = AtomicBoolean(false)
        var browser: MediaBrowserCompat? = null

        // MediaBrowserCompat posts its ConnectionCallback to the Handler of the
        // thread that CONSTRUCTS it. If we built/connected it on the calling
        // thread (which on the voice path is Dispatchers.Main, then blocked on
        // the latch below), the callbacks would queue behind the blocked looper
        // and never fire — the latch would only release on the timeout and this
        // strategy could never succeed. So we give the browser its OWN looper:
        // a dedicated HandlerThread whose looper is never blocked, so the
        // connect callbacks dispatch and the latch releases promptly regardless
        // of which thread play() runs on.
        val handlerThread = HandlerThread("ari-mediabrowser-$pkg").apply { start() }
        val handler = Handler(handlerThread.looper)

        val callback = object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                try {
                    val b = browser
                    if (b != null && b.isConnected) {
                        val controller = MediaControllerCompat(context, b.sessionToken)
                        controller.transportControls.playFromSearch(query, Bundle())
                        dispatched.set(true)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "MediaBrowser dispatch failed for $pkg", t)
                } finally {
                    latch.countDown()
                }
            }

            override fun onConnectionSuspended() {
                latch.countDown()
            }

            override fun onConnectionFailed() {
                Log.w(TAG, "MediaBrowser connection failed for $pkg")
                latch.countDown()
            }
        }

        return try {
            // Construct + connect ON the HandlerThread's looper so the browser
            // binds its internal Handler to that looper rather than the caller's.
            // We block here until construction has happened so `browser` is
            // populated before any callback can read it.
            val constructed = CountDownLatch(1)
            handler.post {
                try {
                    browser = MediaBrowserCompat(context, component, callback, null).also {
                        it.connect()
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "MediaBrowser connect failed for $pkg", t)
                    latch.countDown()
                } finally {
                    constructed.countDown()
                }
            }
            constructed.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val connectedInTime = latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            connectedInTime && dispatched.get()
        } catch (t: Throwable) {
            Log.e(TAG, "MediaBrowser connect failed for $pkg", t)
            false
        } finally {
            // disconnect() must run on the same looper the browser was created
            // on, then tear the thread down so it doesn't leak.
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

    /** Discovers [pkg]'s MediaBrowserService component, or null if it has none. */
    private fun browseServiceComponent(pkg: String): ComponentName? {
        val intent = Intent(MEDIA_BROWSER_SERVICE_ACTION).setPackage(pkg)
        val resolved = context.packageManager.queryIntentServices(intent, 0)
        val info = resolved.firstOrNull()?.serviceInfo ?: return null
        return ComponentName(info.packageName, info.name)
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

        // MEDIA_SESSION spike: after launching the target app, poll for its
        // active session to come up before giving up (~5s total).
        private const val SESSION_POLL_INTERVAL_MS = 300L
        private const val SESSION_POLL_TIMEOUT_MS = 5_000L

        val REGISTRY: Map<String, Service> = listOf(
            Service(
                "spotify", "Spotify", listOf("com.spotify.music"),
                listOf(Strategy.PLAY_FROM_SEARCH_INTENT, Strategy.MEDIA_BROWSER),
            ),
            Service(
                "apple_music", "Apple Music", listOf("com.apple.android.music"),
                listOf(Strategy.MEDIA_BROWSER, Strategy.PLAY_FROM_SEARCH_INTENT),
            ),
            Service(
                "youtube_music", "YouTube Music", listOf("com.google.android.apps.youtube.music"),
                listOf(Strategy.MEDIA_SESSION, Strategy.SEARCH_DEEPLINK),
                searchUrl = { q -> "https://music.youtube.com/search?q=" + Uri.encode(q) },
            ),
            Service(
                "tidal", "Tidal", listOf("com.aspiro.tidal"),
                listOf(Strategy.MEDIA_BROWSER, Strategy.PLAY_FROM_SEARCH_INTENT),
            ),
            Service(
                "deezer", "Deezer", listOf("deezer.android.app"),
                listOf(Strategy.MEDIA_BROWSER, Strategy.PLAY_FROM_SEARCH_INTENT),
            ),
            Service(
                "youtube", "YouTube", listOf("com.google.android.youtube"),
                listOf(Strategy.SEARCH_DEEPLINK, Strategy.PLAY_FROM_SEARCH_INTENT),
                searchUrl = { q -> "https://www.youtube.com/results?search_query=" + Uri.encode(q) },
            ),
            Service(
                "amazon_music", "Amazon Music", listOf("com.amazon.mp3"),
                listOf(Strategy.MEDIA_BROWSER, Strategy.PLAY_FROM_SEARCH_INTENT),
            ),
        ).associateBy { it.id }
    }
}
