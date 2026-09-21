package dev.heyari.ari.debug

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.time.Instant
import kotlin.math.roundToLong

/**
 * Debug-only probe for issue #19: does the platform location stack keep up
 * while Ari's microphone foreground service is running?
 *
 * [dev.heyari.ari.listening.PlaceGeofences] depends on Play Services, and that
 * dependency is what gets Ari's process killed when GrapheneOS's gmscompat
 * goes down. The stated reason for it is that the platform throttles
 * background location to roughly one delivery every thirty minutes, which
 * would make "start listening when I get home" useless.
 *
 * That throttle exempts apps running a foreground service, and Ari runs one
 * whenever listening is anything but Never — including while standing by with
 * the microphone released. So the reason may not apply to Ari at all. This
 * measures it rather than arguing about it.
 *
 * Updates are delivered by PendingIntent rather than to a live listener, which
 * is the same shape a real implementation would use and survives the process
 * dying between fixes. State therefore lives in prefs, not in memory.
 *
 * Start it, put the phone down with the screen off, leave it half an hour:
 *
 *     adb shell am broadcast -a dev.heyari.ari.PROBE_START \
 *       -n dev.heyari.ari/dev.heyari.ari.debug.LocationProbeReceiver
 *
 *     adb logcat -s AriLocProbe           # or watch it live
 *
 *     adb shell am broadcast -a dev.heyari.ari.PROBE_STOP \
 *       -n dev.heyari.ari/dev.heyari.ari.debug.LocationProbeReceiver
 *
 * STOP prints the gaps between deliveries, which is the whole answer: gaps
 * near the requested interval mean no throttle and the platform is a viable
 * replacement. Gaps collapsing to one or two an hour mean the class doc was
 * right and Play Services stays.
 *
 * Optional extras on START:
 *   --ei interval_s 60     how often to ask for a fix (default 60)
 *   --ei radius_m 150      proximity alert radius around the starting point
 *
 * The proximity alert is the second half: walk out past the radius and back,
 * and the log will say how long each transition took to arrive. That is the
 * number "when I get home" actually cares about, and it needs you to move —
 * the interval test above does not.
 */
class LocationProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> start(context, intent)
            ACTION_STOP -> stop(context)
            ACTION_FIX -> onFix(context, intent)
            ACTION_PROXIMITY -> onProximity(context, intent)
        }
    }

    @SuppressLint("MissingPermission")
    private fun start(context: Context, intent: Intent) {
        if (!hasPermission(context)) {
            say(context, "ACCESS_FINE_LOCATION not granted — nothing to measure")
            return
        }
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val intervalS = intent.getIntExtra("interval_s", DEFAULT_INTERVAL_S)
        val radiusM = intent.getIntExtra("radius_m", DEFAULT_RADIUS_M).toFloat()

        prefs(context).edit().clear().putLong(KEY_STARTED_AT, System.currentTimeMillis()).apply()
        File(context.filesDir, LOG_FILE).delete()

        // The platform's own fused provider where there is one: it is the like
        // for like comparison against the Play Services client, rather than
        // against raw GPS. Older devices get the network provider.
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                lm.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.allProviders.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.GPS_PROVIDER
        }

        runCatching {
            lm.requestLocationUpdates(
                provider,
                intervalS * 1000L,
                0f,
                pending(context, ACTION_FIX, REQUEST_FIX),
            )
        }.onFailure {
            say(context, "could not request updates from $provider: $it")
            return
        }

        val anchor = runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        if (anchor != null) {
            runCatching {
                lm.addProximityAlert(
                    anchor.latitude,
                    anchor.longitude,
                    radiusM,
                    -1L,
                    pending(context, ACTION_PROXIMITY, REQUEST_PROXIMITY),
                )
            }.onFailure { say(context, "could not add proximity alert: $it") }
            say(context, "proximity alert armed at ${anchor.latitude},${anchor.longitude} r=${radiusM}m")
        } else {
            say(context, "no last-known fix to anchor a proximity alert — interval test only")
        }

        say(context, "started: provider=$provider interval=${intervalS}s")
    }

    private fun stop(context: Context) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        runCatching { lm.removeUpdates(pending(context, ACTION_FIX, REQUEST_FIX)) }
        runCatching { lm.removeProximityAlert(pending(context, ACTION_PROXIMITY, REQUEST_PROXIMITY)) }

        val p = prefs(context)
        val started = p.getLong(KEY_STARTED_AT, 0L)
        val count = p.getInt(KEY_COUNT, 0)
        val gaps = p.getString(KEY_GAPS, "").orEmpty()
            .split(',').filter { it.isNotBlank() }.mapNotNull { it.toLongOrNull() }

        val spanS = if (started > 0) (System.currentTimeMillis() - started) / 1000 else 0
        say(context, "stopped after ${spanS}s with $count fix(es)")
        if (gaps.isEmpty()) {
            say(context, "no gaps recorded — either nothing arrived, or only one fix did")
        } else {
            val sorted = gaps.sorted()
            say(
                context,
                "gaps between fixes (s): min=${sorted.first()} " +
                    "median=${sorted[sorted.size / 2]} max=${sorted.last()}",
            )
            say(context, "all gaps (s): ${gaps.joinToString(", ")}")
        }
        say(context, "verdict is yours: gaps near the requested interval mean no throttle")
    }

    private fun onFix(context: Context, intent: Intent) {
        val location: Location? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED, Location::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED)
            }
        if (location == null) return

        val p = prefs(context)
        val now = System.currentTimeMillis()
        val last = p.getLong(KEY_LAST_AT, 0L)
        val count = p.getInt(KEY_COUNT, 0) + 1
        val edit = p.edit().putLong(KEY_LAST_AT, now).putInt(KEY_COUNT, count)

        val gapS = if (last > 0) ((now - last) / 1000.0).roundToLong() else -1L
        if (gapS >= 0) {
            edit.putString(KEY_GAPS, p.getString(KEY_GAPS, "").orEmpty() + "$gapS,")
        }
        edit.apply()

        say(
            context,
            "fix #$count from ${location.provider} " +
                "±${location.accuracy.roundToLong()}m" +
                if (gapS >= 0) " (${gapS}s since the last)" else " (first)",
        )
    }

    private fun onProximity(context: Context, intent: Intent) {
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        say(context, "proximity alert: ${if (entering) "ENTERED" else "LEFT"} the anchor radius")
    }

    /**
     * FLAG_MUTABLE because the system writes the fix into these extras; an
     * immutable one would arrive empty. Explicit component so the broadcast
     * comes back to this receiver rather than going looking.
     */
    private fun pending(context: Context, action: String, request: Int): PendingIntent {
        val intent = Intent(context, LocationProbeReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            request,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** To logcat for watching live, and to a file for reading after a night of it. */
    private fun say(context: Context, line: String) {
        Log.i(TAG, line)
        runCatching {
            File(context.filesDir, LOG_FILE).appendText("${Instant.now()} $line\n")
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("location_probe", Context.MODE_PRIVATE)

    private companion object {
        const val TAG = "AriLocProbe"
        const val LOG_FILE = "location-probe.log"

        const val ACTION_START = "dev.heyari.ari.PROBE_START"
        const val ACTION_STOP = "dev.heyari.ari.PROBE_STOP"
        const val ACTION_FIX = "dev.heyari.ari.PROBE_FIX"
        const val ACTION_PROXIMITY = "dev.heyari.ari.PROBE_PROXIMITY"

        const val REQUEST_FIX = 9001
        const val REQUEST_PROXIMITY = 9002

        const val DEFAULT_INTERVAL_S = 60
        const val DEFAULT_RADIUS_M = 150

        const val KEY_STARTED_AT = "started_at"
        const val KEY_LAST_AT = "last_at"
        const val KEY_COUNT = "count"
        const val KEY_GAPS = "gaps"
    }
}
