package dev.heyari.ari.listening

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether the device is inside any [ListeningPlace], using Play Services
 * geofencing.
 *
 * Geofencing is the whole reason this is worth doing at all: polling location to
 * decide whether to run the microphone would cost more battery than the
 * microphone. The platform's own `LocationManager.addProximityAlert` exists and
 * isn't deprecated, but its background throttle defaults to thirty minutes —
 * "start listening when I get home" arriving half an hour late is not a feature.
 * So this path needs Play Services, and says so plainly when they're missing
 * rather than failing silently.
 */
@Singleton
class PlaceGeofences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val client by lazy { LocationServices.getGeofencingClient(context) }
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

    private val insideIds = MutableStateFlow<Set<String>>(emptySet())

    private var seedCallback: LocationCallback? = null

    // Transitions arrive as bare geofence ids on a broadcast thread, so the
    // names have to be looked up against whatever is currently registered.
    @Volatile
    private var registered: List<ListeningPlace> = emptyList()

    /** Names of the registered places the device is currently inside. */
    val insidePlaceNames: Flow<List<String>> = insideIds
        .map { ids -> registered.filter { it.id in ids }.map { it.name } }
        .distinctUntilChanged()

    fun playServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS

    /**
     * Geofencing needs fine location AND background location. Coarse is not
     * enough, and without the background grant the location app-op resolves to
     * ignored the moment we leave the foreground — which is precisely when a
     * geofence is supposed to earn its keep.
     */
    fun hasPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val background = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine && background
    }

    @SuppressLint("MissingPermission") // gated on hasPermissions() immediately below
    fun register(places: List<ListeningPlace>) {
        clear()
        if (places.isEmpty()) return
        if (!playServicesAvailable()) {
            Log.w(TAG, "Play Services unavailable — place-based listening is off")
            return
        }
        if (!hasPermissions()) {
            Log.w(TAG, "Fine + background location not granted — place-based listening is off")
            return
        }

        val fenced = places.take(ListeningPlace.MAX_PLACES)
        registered = fenced

        val fences = fenced.map { place ->
            Geofence.Builder()
                .setRequestId(place.id)
                .setCircularRegion(place.latitude, place.longitude, place.radiusMetres)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                // Five minutes of slack. The docs are explicit that a larger
                // responsiveness saves significant power, and against a
                // listening window measured in hours it costs nothing.
                .setNotificationResponsiveness(RESPONSIVENESS_MS)
                .build()
        }

        val request = GeofencingRequest.Builder()
            // Report immediately if we're already standing inside one, rather
            // than waiting for the user to walk out and back in again.
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(fences)
            .build()

        client.addGeofences(request, transitionPendingIntent())
            .addOnFailureListener { Log.w(TAG, "Failed to register geofences", it) }

        seedFromCurrentLocation(fenced)
    }

    /**
     * Work out where we are *now* instead of waiting to be told.
     *
     * A geofence only ever reports a crossing, and [insideIds] lives in memory,
     * so a service or process restart while sat at home leaves us believing
     * we're nowhere — with nothing able to correct it until the user physically
     * walks out and back in again. INITIAL_TRIGGER_ENTER is meant to cover
     * exactly that and doesn't do it reliably: Play Services defers the initial
     * evaluation to its next location sample, and [RESPONSIVENESS_MS] asks for
     * five more minutes of slack on top.
     *
     * A short burst of updates rather than a single fix, because one fix is the
     * opening guess and not the answer: `getCurrentLocation` returns as soon as
     * anything satisfies the priority, which indoors is a ±200m cell-and-wifi
     * estimate that settles nothing about a 100m circle. Accuracy converges over
     * the following seconds — the same reason the dot in a maps app starts vague
     * and tightens onto the building. So keep listening until a fix is sharp
     * enough to decide, and stop the moment one is. Costly for a few seconds,
     * once per registration, against a feature that otherwise holds a
     * microphone open all day.
     */
    @SuppressLint("MissingPermission") // gated on hasPermissions() in register()
    private fun seedFromCurrentLocation(places: List<ListeningPlace>) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, SEED_INTERVAL_MS)
            .setMinUpdateIntervalMillis(SEED_INTERVAL_MS)
            // Expires itself, so a seed that never converges can't leave the
            // GPS running behind us.
            .setDurationMillis(SEED_TIMEOUT_MS)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val inside = places.filter { it.confidentlyContains(location) }
                // "Nowhere near any of them" has to be proved too, not assumed
                // from the absence of a positive: a ±99m fix rules you out of a
                // 100m circle exactly as poorly as it rules you into one.
                val decided = inside.isNotEmpty() || places.all { it.confidentlyExcludes(location) }
                if (!decided) {
                    Log.i(TAG, "Seed fix ±${location.accuracy.toInt()}m — too vague, waiting for a better one")
                    return
                }
                Log.i(
                    TAG,
                    "Seeded from ${location.provider} (±${location.accuracy.toInt()}m): " +
                        "inside ${inside.size} of ${places.size} place(s)",
                )
                insideIds.value = inside.map { it.id }.toSet()
                stopSeed()
            }
        }
        stopSeed()
        seedCallback = callback
        locationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { Log.w(TAG, "Couldn't seed place state from current location", it) }
    }

    private fun stopSeed() {
        seedCallback?.let { locationClient.removeLocationUpdates(it) }
        seedCallback = null
    }

    fun clear() {
        stopSeed()
        registered = emptyList()
        insideIds.value = emptySet()
        if (!playServicesAvailable()) return
        client.removeGeofences(transitionPendingIntent())
            .addOnFailureListener { Log.w(TAG, "Failed to remove geofences", it) }
    }

    internal fun onTransition(event: GeofencingEvent) {
        if (event.hasError()) {
            Log.w(TAG, "Geofence event error code ${event.errorCode}")
            return
        }
        val ids = event.triggeringGeofences?.map { it.requestId }?.toSet() ?: return
        // The thing the seed was standing in for has spoken, and it is the
        // better authority — Play Services has been watching continuously,
        // where we get one burst. Let it go before a late fix overwrites this.
        stopSeed()
        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> insideIds.update { it + ids }
            Geofence.GEOFENCE_TRANSITION_EXIT -> insideIds.update { it - ids }
            else -> Log.w(TAG, "Ignoring geofence transition ${event.geofenceTransition}")
        }
    }

    private fun transitionPendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
            .setAction(GeofenceReceiver.ACTION_TRANSITION)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_TRANSITION,
            intent,
            // Mutable: Play Services fills the transition details in on delivery.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private companion object {
        const val TAG = "PlaceGeofences"
        const val REQUEST_TRANSITION = 0
        const val RESPONSIVENESS_MS = 5 * 60 * 1000

        const val SEED_INTERVAL_MS = 1000L
        const val SEED_TIMEOUT_MS = 30 * 1000L
    }
}

/**
 * The two answers the seed is allowed to give, each requiring the fix's whole
 * uncertainty circle to fall on one side of the boundary.
 *
 * Ignoring accuracy reads a ±80m fix as if it were a survey peg, which is how a
 * phone sat in the kitchen gets ruled out of its own 100m home circle. Anything
 * the fix can't settle either way isn't an answer, and the geofence's own
 * crossing decides it later — the seed is a fast path, not the only one.
 */
private fun ListeningPlace.confidentlyContains(location: Location): Boolean =
    distanceFrom(location) + location.accuracy <= radiusMetres

private fun ListeningPlace.confidentlyExcludes(location: Location): Boolean =
    distanceFrom(location) - location.accuracy > radiusMetres

private fun ListeningPlace.distanceFrom(location: Location): Float {
    val metres = FloatArray(1)
    Location.distanceBetween(latitude, longitude, location.latitude, location.longitude, metres)
    return metres[0]
}

@AndroidEntryPoint
class GeofenceReceiver : BroadcastReceiver() {

    @Inject
    lateinit var placeGeofences: PlaceGeofences

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        placeGeofences.onTransition(event)
    }

    companion object {
        const val ACTION_TRANSITION = "dev.heyari.ari.GEOFENCE_TRANSITION"
    }
}
