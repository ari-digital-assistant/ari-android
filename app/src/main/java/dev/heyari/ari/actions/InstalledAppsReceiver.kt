package dev.heyari.ari.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import uniffi.ari_ffi.AriEngine

/**
 * Re-pushes the launchable-app inventory when an app is installed or removed,
 * so "open <newly installed app>" resolves without an app restart. Best-effort:
 * if the engine isn't built yet, the build-time push already carried a fresh list.
 */
class InstalledAppsReceiver(
    private val appLauncher: AppLauncher,
    private val engineProvider: () -> AriEngine?,
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = engineProvider() ?: return
        // listLaunchable() is a PackageManager query and pushInstalledApps hops
        // the FFI — keep both off the main thread. goAsync() holds the broadcast
        // open until finish() (well within the receiver's ~10s window).
        val pending = goAsync()
        Thread {
            try {
                engine.pushInstalledApps(appLauncher)
                Log.i("InstalledAppsReceiver", "refreshed inventory after ${intent.action}")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
