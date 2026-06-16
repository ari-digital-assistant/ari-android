package dev.heyari.ari.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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

    fun resolve(hasPermission: Boolean, fix: Fix?, nowMs: Long): FfiLocationResult = when {
        !hasPermission -> err(FfiLocationStatus.PERMISSION_DENIED)
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
            return LocationLogic.resolve(hasPermission = false, fix = null, nowMs = 0L)
        }
        val nowMs = System.currentTimeMillis()
        val fix = runCatching { lastKnownFreshEnough(maxAgeMs, nowMs) ?: activeFix(timeoutMs) }
            .getOrNull()
        return LocationLogic.resolve(hasPermission = true, fix = fix, nowMs = nowMs)
    }

    private fun lastKnownFreshEnough(maxAgeMs: Long, nowMs: Long): LocationLogic.Fix? {
        val loc = runCatching { Tasks.await(client.lastLocation, 2, TimeUnit.SECONDS) }.getOrNull()
            ?: return null
        val ageMs = nowMs - loc.time
        return if (ageMs in 0..maxAgeMs) loc.toFix() else null
    }

    private fun activeFix(timeoutMs: Long): LocationLogic.Fix? {
        val cts = CancellationTokenSource()
        val task = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
        val loc = runCatching {
            Tasks.await(task, timeoutMs, TimeUnit.MILLISECONDS)
        }.getOrElse {
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
}
