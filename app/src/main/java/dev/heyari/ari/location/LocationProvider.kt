package dev.heyari.ari.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import dagger.hilt.android.qualifiers.ApplicationContext
import uniffi.ari_ffi.FfiLocationResult
import uniffi.ari_ffi.FfiLocationStatus
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure decision logic for turning a permission state + an optional fix
 * into an [FfiLocationResult]. Kept free of Android I/O so it unit-tests
 * without a device. Contract: only [FfiLocationStatus.OK] carries real
 * coordinates; every other status zeroes them.
 */
object LocationLogic {
    data class Fix(val lat: Double, val lon: Double, val accuracyM: Double, val timeMs: Long)

    fun resolve(hasPermission: Boolean, servicesAvailable: Boolean, fix: Fix?): FfiLocationResult = when {
        !hasPermission -> err(FfiLocationStatus.PERMISSION_DENIED)
        !servicesAvailable -> err(FfiLocationStatus.UNAVAILABLE)
        fix == null -> err(FfiLocationStatus.TIMEOUT)
        else -> FfiLocationResult(
            status = FfiLocationStatus.OK,
            lat = fix.lat,
            lon = fix.lon,
            accuracyM = fix.accuracyM,
            timestampMs = fix.timeMs,
        )
    }

    private fun err(status: FfiLocationStatus) =
        FfiLocationResult(status, 0.0, 0.0, 0.0, 0L)
}

/**
 * Coarse device location via FusedLocation. Returns a cached last-known
 * fix when it's fresh enough, else requests a single balanced-power fix
 * with a timeout. Coarse only — never requests fine location.
 */
@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    fun hasCoarsePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Blocking coarse fix. Honours [maxAgeMs] (last-known) then [timeoutMs] (active). */
    fun current(maxAgeMs: Long, timeoutMs: Long): FfiLocationResult {
        if (!hasCoarsePermission()) {
            return LocationLogic.resolve(hasPermission = false, servicesAvailable = false, fix = null)
        }
        if (!locationServicesAvailable()) {
            return LocationLogic.resolve(hasPermission = true, servicesAvailable = false, fix = null)
        }
        val nowMs = System.currentTimeMillis()
        val lastKnown = lastKnownLocation()
        val freshFix = lastKnown?.takeIf { (nowMs - it.time) in 0..maxAgeMs }?.toFix()
        // Degrade gracefully: prefer a fresh cached fix, else actively request
        // one, else fall back to whatever last-known fix we have — however old.
        // A stale coarse location still beats failing entirely: weather (and
        // the other coarse-location skills) only need city-level, slowly-
        // changing position, and the active request can come up empty for
        // reasons that have nothing to do with the user (e.g. the network-
        // location backend is off, so a BALANCED_POWER request has no source
        // even though a perfectly good GPS fix is cached). The returned
        // timestamp stays honest, so a caller that genuinely needs freshness
        // can still judge the age itself.
        val fix = freshFix ?: activeFix(timeoutMs) ?: lastKnown?.toFix()
        return LocationLogic.resolve(hasPermission = true, servicesAvailable = true, fix = fix)
    }

    /**
     * True only when the device can actually service a location request:
     * Google Play Services present and the system location master switch
     * on. False here is the difference between [FfiLocationStatus.UNAVAILABLE]
     * (can't even try) and [FfiLocationStatus.TIMEOUT] (tried, no fix in time).
     */
    private fun locationServicesAvailable(): Boolean {
        // Google Play Services present?
        val play = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (play != ConnectionResult.SUCCESS) return false
        // System location master switch on?
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    /** The fused last-known location, or null. Fetched once and reused for
     *  both the fresh-enough fast path and the stale fallback. */
    private fun lastKnownLocation(): android.location.Location? =
        runCatching { Tasks.await(client.lastLocation, 2, TimeUnit.SECONDS) }
            .onFailure { e -> Log.w(TAG, "last-known location lookup failed: ${e.message}") }
            .getOrNull()

    private fun activeFix(timeoutMs: Long): LocationLogic.Fix? {
        val cts = CancellationTokenSource()
        val task = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
        val loc = runCatching {
            Tasks.await(task, timeoutMs, TimeUnit.MILLISECONDS)
        }.getOrElse { e ->
            Log.w(TAG, "active location fix failed: ${e.message}")
            cts.cancel()
            null
        } ?: return null
        return loc.toFix()
    }

    private fun android.location.Location.toFix() = LocationLogic.Fix(
        lat = latitude,
        lon = longitude,
        accuracyM = if (hasAccuracy()) accuracy.toDouble() else 0.0,
        timeMs = time,
    )

    companion object {
        private const val TAG = "LocationProvider"
    }
}
