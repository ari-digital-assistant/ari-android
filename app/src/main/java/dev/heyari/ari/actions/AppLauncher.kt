package dev.heyari.ari.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Looks up installed launchable apps and tries to match a free-text target like
 * "spotify" or "google chrome" against their display labels.
 *
 * Uses the manifest `<queries>` LAUNCHER intent declaration so it works on
 * Android 11+ without QUERY_ALL_PACKAGES.
 */
@Singleton
class AppLauncher @Inject constructor(
    private val context: Context,
) {

    data class LaunchableApp(
        val packageName: String,
        val label: String,
    )

    sealed interface LaunchResult {
        data class Launched(val app: LaunchableApp) : LaunchResult
        data class NotFound(val target: String) : LaunchResult
        data class Failed(val app: LaunchableApp, val reason: String) : LaunchResult
    }

    /** Returns every app that has a launcher icon. */
    fun listLaunchable(): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved: List<ResolveInfo> = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

        return resolved.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            val label = info.loadLabel(pm).toString()
            LaunchableApp(packageName = pkg, label = label)
        }
    }

    /**
     * Resolve a free-text target against installed apps. Strategy:
     * 1. Exact case-insensitive match on label
     * 2. Label starts with target
     * 3. Label contains all words of target (in any order)
     * 4. Package name contains target (last resort, e.g. "chrome" → com.android.chrome)
     */
    fun findApp(target: String): LaunchableApp? {
        val needle = target.trim().lowercase()
        if (needle.isEmpty()) return null

        val apps = listLaunchable()
        val needleWords = needle.split(Regex("\\s+")).filter { it.isNotBlank() }

        // 1. Exact match
        apps.firstOrNull { it.label.equals(needle, ignoreCase = true) }?.let { return it }

        // 2. Prefix match
        apps.firstOrNull { it.label.lowercase().startsWith(needle) }?.let { return it }

        // 3. All words contained
        apps.firstOrNull { app ->
            val labelLower = app.label.lowercase()
            needleWords.all { labelLower.contains(it) }
        }?.let { return it }

        // 4. Package name fallback
        apps.firstOrNull { app ->
            val pkgLower = app.packageName.lowercase()
            needleWords.all { pkgLower.contains(it) }
        }?.let { return it }

        return null
    }

    fun launch(target: String): LaunchResult {
        val app = findApp(target) ?: return LaunchResult.NotFound(target)

        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return LaunchResult.Failed(app, "no launch intent")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            Log.i(TAG, "Launched ${app.label} (${app.packageName})")
            LaunchResult.Launched(app)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to launch ${app.packageName}", t)
            LaunchResult.Failed(app, t.message ?: "unknown error")
        }
    }

    companion object {
        private const val TAG = "AppLauncher"
    }
}
