package dev.heyari.ari.listening

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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

    private var seedCancellation: CancellationTokenSource? = null

    /** True while the device is inside at least one registered place. */
    val atAnyPlace: Flow<Boolean> = insideIds.map { it.isNotEmpty() }.distinctUntilChanged()

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

        val fences = places.take(ListeningPlace.MAX_PLACES).map { place ->
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

        seedFromCurrentLocation(places)
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
     * five more minutes of slack on top. One fix answers the question outright.
     */
    @SuppressLint("MissingPermission") // gated on hasPermissions() in register()
    private fun seedFromCurrentLocation(places: List<ListeningPlace>) {
        val cancellation = CancellationTokenSource()
        seedCancellation = cancellation
        locationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellation.token)
            .addOnSuccessListener { location ->
                if (location == null) {
                    Log.w(TAG, "No fix to seed place state from — waiting for a crossing instead")
                    return@addOnSuccessListener
                }
                insideIds.value = places.filter { it.contains(location) }.map { it.id }.toSet()
            }
            .addOnFailureListener { Log.w(TAG, "Couldn't seed place state from current location", it) }
    }

    fun clear() {
        seedCancellation?.cancel()
        seedCancellation = null
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
    }
}

/**
 * Plain containment, with no allowance for the fix's own accuracy: a loose fix
 * near the edge should leave the microphone shut, not open it somewhere the
 * user never marked.
 */
private fun ListeningPlace.contains(location: Location): Boolean {
    val distance = FloatArray(1)
    Location.distanceBetween(latitude, longitude, location.latitude, location.longitude, distance)
    return distance[0] <= radiusMetres
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
