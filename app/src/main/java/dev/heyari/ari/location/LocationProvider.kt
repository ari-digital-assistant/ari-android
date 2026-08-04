package dev.heyari.ari.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import dagger.hilt.android.qualifiers.ApplicationContext
import uniffi.ari_ffi.FfiLocationResult
import uniffi.ari_ffi.FfiLocationStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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

    /**
     * The most recently-taken of [fixes], ignoring nulls. Used to collapse a
     * per-provider sweep of the platform LocationManager, which answers for
     * each provider separately and has no notion of a single best guess.
     */
    fun freshest(fixes: List<Fix?>): Fix? = fixes.filterNotNull().maxByOrNull { it.timeMs }
}

/**
 * Coarse device location. Returns a cached last-known fix when it's fresh
 * enough, else requests a single low-power fix with a timeout, else falls back
 * to a stale fix. Coarse only — never requests fine location.
 *
 * Prefers FusedLocation when Play Services is present, and the platform
 * LocationManager when it isn't, so location still works on a de-Googled
 * build instead of reporting the device incapable.
 *
 * Both paths block, so [current] must be called off the main thread —
 * `EngineHolder.processInput` is what guarantees that for skill callbacks.
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
        // The system location master switch, which no fix source can work
        // around. This is the difference between [FfiLocationStatus.UNAVAILABLE]
        // (can't even try) and [FfiLocationStatus.TIMEOUT] (tried, nothing in time).
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null || !LocationManagerCompat.isLocationEnabled(lm)) {
            return LocationLogic.resolve(hasPermission = true, servicesAvailable = false, fix = null)
        }
        // Refuse loudly rather than report a timeout that never happened. Both
        // fix sources below block, and FusedLocation's `Tasks.await` throws
        // outright on the main thread — which is how every voice-path weather
        // query came back "I don't know where you are" while the same question
        // typed into the chat worked. If this line ever fires again, the caller
        // has stopped going through EngineHolder.processInput.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "current() called on the main thread — no fix is obtainable here")
            return LocationLogic.resolve(hasPermission = true, servicesAvailable = false, fix = null)
        }

        val fused = playServicesAvailable()
        val lastKnown = if (fused) fusedLastKnown() else platformLastKnown(lm)
        val nowMs = System.currentTimeMillis()
        val freshFix = lastKnown?.takeIf { (nowMs - it.timeMs) in 0..maxAgeMs }
        // Degrade gracefully: prefer a fresh cached fix, else actively request
        // one, else fall back to whatever last-known fix we have — however old.
        // A stale coarse location still beats failing entirely: weather (and
        // the other coarse-location skills) only need city-level, slowly-
        // changing position, and the active request can come up empty for
        // reasons that have nothing to do with the user (e.g. the network-
        // location backend is off, so a low-power request has no source
        // even though a perfectly good GPS fix is cached). The returned
        // timestamp stays honest, so a caller that genuinely needs freshness
        // can still judge the age itself.
        val fix = freshFix
            ?: (if (fused) fusedActiveFix(timeoutMs) else platformActiveFix(lm, timeoutMs))
            ?: lastKnown
        return LocationLogic.resolve(hasPermission = true, servicesAvailable = true, fix = fix)
    }

    /**
     * Play Services present and usable. When it isn't — a de-Googled or
     * GrapheneOS build, or an in-progress Play Services update — we use the
     * platform providers instead of declaring the device incapable.
     */
    private fun playServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /** The fused last-known fix, or null. Fetched once and reused for both the
     *  fresh-enough fast path and the stale fallback. */
    private fun fusedLastKnown(): LocationLogic.Fix? =
        runCatching { Tasks.await(client.lastLocation, 2, TimeUnit.SECONDS) }
            .onFailure { e -> Log.w(TAG, "fused last-known lookup failed", e) }
            .getOrNull()
            ?.toFix()

    private fun fusedActiveFix(timeoutMs: Long): LocationLogic.Fix? {
        val cts = CancellationTokenSource()
        val task = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
        return runCatching { Tasks.await(task, timeoutMs, TimeUnit.MILLISECONDS) }
            .onFailure { e ->
                Log.w(TAG, "fused active fix failed", e)
                cts.cancel()
            }
            .getOrNull()
            ?.toFix()
    }

    /**
     * Newest last-known fix across every enabled platform provider. Asked
     * per-provider because which ones a coarse-only grant may read varies by
     * API level and OEM — one throwing must not cost us the others.
     */
    @SuppressLint("MissingPermission") // gated on hasCoarsePermission() in current()
    private fun platformLastKnown(lm: LocationManager): LocationLogic.Fix? =
        LocationLogic.freshest(
            lm.getProviders(true).map { provider ->
                runCatching { lm.getLastKnownLocation(provider) }
                    .onFailure { e -> Log.w(TAG, "last-known from $provider unavailable", e) }
                    .getOrNull()
                    ?.toFix()
            },
        )

    @SuppressLint("MissingPermission") // gated on hasCoarsePermission() in current()
    private fun platformActiveFix(lm: LocationManager, timeoutMs: Long): LocationLogic.Fix? {
        // NETWORK_PROVIDER is the coarse, low-power one and the right default;
        // anything else enabled beats giving up.
        val provider = LocationManager.NETWORK_PROVIDER
            .takeIf { LocationManagerCompat.hasProvider(lm, it) && lm.isProviderEnabled(it) }
            ?: lm.getProviders(true).firstOrNull()
            ?: return null

        val signal = CancellationSignal()
        val latch = CountDownLatch(1)
        val result = AtomicReference<android.location.Location?>()
        try {
            // Direct executor: the callback only stores a reference and drops
            // the latch, and a Looper-bound executor would deadlock against
            // the thread waiting below if they were ever the same Looper.
            LocationManagerCompat.getCurrentLocation(lm, provider, signal, Executor { it.run() }) { loc ->
                result.set(loc)
                latch.countDown()
            }
        } catch (e: SecurityException) {
            // The grant can be revoked between current()'s check and here.
            Log.w(TAG, "platform active fix on $provider denied", e)
            return null
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            signal.cancel()
            Log.w(TAG, "platform active fix on $provider timed out after ${timeoutMs}ms")
            return null
        }
        return result.get()?.toFix()
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
