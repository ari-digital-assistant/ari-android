package dev.heyari.ari.listening

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
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
 * geofencing, Ari's own checks on network location, or both.
 *
 * Neither may ever ask for GPS. The point of listening by place is to keep the
 * microphone shut most of the day, and GPS hunting for a fix indoors costs more
 * than the microphone does. Network location needs no satellite and is cheap
 * enough to ask for every few minutes.
 */
@Singleton
class PlaceGeofences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val client by lazy { LocationServices.getGeofencingClient(context) }

    private val insideIds = MutableStateFlow<Set<String>>(emptySet())

    private val seedHandler = Handler(Looper.getMainLooper())

    private var seedBackstop: Runnable? = null

    private var wakeReceiver: BroadcastReceiver? = null

    // The seed's listener, kept so [stopSeed] can shut the burst down.
    private var platformSeedListener: LocationListener? = null

    // Which fences to register. Set by [register] before anything is armed, and
    // read again on every [refreshIfStale] re-arm.
    @Volatile
    private var source: PlaceFenceSource = PlaceFenceSource.PLAY

    // When the geofencer or a seed last told us anything. Elapsed-realtime
    // rather than uptime, because the Doze that silences the geofencer is
    // exactly the time that has to count towards going stale.
    @Volatile
    private var lastConfirmedAt = 0L

    // Transitions arrive as bare geofence ids on a broadcast thread, so the
    // names have to be looked up against whatever is currently registered.
    @Volatile
    private var registered: List<ListeningPlace> = emptyList()

    /** Names of the registered places the device is currently inside. */
    val insidePlaceNames: Flow<List<String>> = insideIds
        .map { ids -> registered.filter { it.id in ids }.map { it.name } }
        .distinctUntilChanged()

    /**
     * Tell the main process where we are.
     *
     * This class runs in `:gms`, which is the process that dies whenever the
     * Play Services stack cycles. The listening decision lives in the main
     * process, so the answer has to cross over, by broadcast rather than by
     * anything the main process could be killed for depending on.
     *
     * Called at every mutation rather than from a collector, because a
     * transition can arrive into a freshly-woken process with nothing
     * collecting anything yet.
     */
    private fun publish() {
        val names = registered.filter { it.id in insideIds.value }.map { it.name }
        context.sendBroadcast(
            Intent(ACTION_PLACE_STATE)
                .setPackage(context.packageName)
                .putStringArrayListExtra(EXTRA_PLACE_NAMES, ArrayList(names))
        )
    }

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

    fun register(places: List<ListeningPlace>, source: PlaceFenceSource) {
        clear()
        this.source = source
        if (places.isEmpty()) return
        arm(places.take(ListeningPlace.MAX_PLACES))
    }

    /**
     * Hand the fences to Play Services and work out where we are right now.
     *
     * Re-arming with the same request ids replaces the existing fences instead
     * of duplicating them, so [refreshIfStale] can come straight back through
     * here without ever leaving a window with nothing registered.
     */
    @SuppressLint("MissingPermission") // gated on hasPermissions() immediately below
    private fun arm(places: List<ListeningPlace>) {
        // Falling back rather than switching the feature off: somebody who
        // picked Play fences on a device that turns out not to have them is
        // better served by the worse mechanism than by silence.
        val usePlay = source.usesPlay && playServicesAvailable()
        val usePlatform = source.usesPlatform || !usePlay
        if (source.usesPlay && !usePlay) {
            Log.w(TAG, "Play Services unavailable — falling back to platform fences")
        }
        if (!hasPermissions()) {
            Log.w(TAG, "Fine + background location not granted — place-based listening is off")
            return
        }

        registered = places
        lastConfirmedAt = SystemClock.elapsedRealtime()
        watchForWake()

        clearLegacyProximityAlerts(places)
        if (usePlatform) watchNetworkLocation(places)
        if (usePlay) armPlayFences(places)
        seedFromCurrentLocation(places)
    }

    @SuppressLint("MissingPermission") // gated on hasPermissions() in arm()
    private fun armPlayFences(places: List<ListeningPlace>) {
        val fences = places.map { place ->
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
    }

    /**
     * Ari's own fences, alongside the Play Services ones rather than instead of
     * them: a network fix every [WATCH_INTERVAL_MS], tested against each circle
     * in [onNetworkFix]. Watching a different stack, the two fail at different
     * times. Where Play Services is sandboxed and working off a ±300m fix, a
     * ±10m platform fix is routinely the one that notices.
     *
     * Network and never GPS. These replace the platform's proximity alerts,
     * which ask the fused provider for a balanced fix every half hour, and where
     * that provider answers with GPS, as GrapheneOS's does, the GPS spent hours
     * a day failing to find a fix indoors: 94% of 137,570 attempts over 3.5
     * days on the test phone. Network location there was the sharper of the
     * two anyway, ±6m against ±12m.
     *
     * Re-requesting on the same PendingIntent replaces the request rather than
     * doubling it, so a re-arm needs no teardown first.
     */
    @SuppressLint("MissingPermission") // gated on hasPermissions() in arm()
    private fun watchNetworkLocation(places: List<ListeningPlace>) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            Log.w(TAG, "Network location is off, so Ari's own place checks can't run")
            return
        }
        runCatching {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, WATCH_INTERVAL_MS, 0f, networkPendingIntent())
        }.onFailure { Log.w(TAG, "Couldn't start network place checks", it) }
        Log.i(TAG, "Checking ${places.size} place(s) on network location every ${WATCH_INTERVAL_MS / 60_000} min")
    }

    private fun stopNetworkWatch() {
        runCatching { context.getSystemService(LocationManager::class.java)?.removeUpdates(networkPendingIntent()) }
            .onFailure { Log.w(TAG, "Failed to stop network place checks", it) }
    }

    /**
     * Builds before [watchNetworkLocation] registered proximity alerts, and each
     * one holds a location request open in the system until it is removed or the
     * phone restarts: an app update leaves them standing. Whichever fences are
     * chosen now, so is this. Removing one that isn't there is a no-op.
     */
    private fun clearLegacyProximityAlerts(places: List<ListeningPlace>) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        for (place in places) {
            runCatching { lm.removeProximityAlert(legacyProximityPendingIntent(place.id)) }
                .onFailure { Log.w(TAG, "Failed to remove old platform fence for ${place.name}", it) }
        }
    }

    /**
     * Re-arm and re-check once the place state has gone quiet for too long.
     *
     * [insideIds] only moves when a crossing is delivered, so anything that
     * stops them arriving — Doze parking the geofencer, Play Services
     * restarting underneath us, a fence quietly lost — freezes it on whatever
     * it last knew, with nothing able to correct it. That is the shade still
     * saying "you're at Home" hours after leaving, and it is why switching the
     * mode off and back on clears it: that path re-registers.
     *
     * So do what that workaround does, on a trigger the user doesn't have to
     * think about. The current state stays standing until the new fences or the
     * seed say otherwise — blanking it first would close the microphone and
     * reopen it seconds later for nothing.
     */
    private fun refreshIfStale() {
        val places = registered
        if (places.isEmpty()) return
        val quietMs = SystemClock.elapsedRealtime() - lastConfirmedAt
        if (quietMs < STALE_AFTER_MS) return
        Log.i(TAG, "No place news for ${quietMs / 60_000} min — re-arming")
        arm(places)
    }

    /**
     * Screen-on is the cheap way to ask whether this device has been sat in a
     * drawer. A repair pass wants a moment when the device is awake anyway and
     * someone is about to read the notification, and wants to cost nothing the
     * rest of the time — a poll or a repeating alarm would spend battery on
     * every idle hour to catch a case that only shows when someone looks.
     */
    private fun watchForWake() {
        if (wakeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) = refreshIfStale()
        }
        // A protected system broadcast — it cannot be declared in the manifest.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        wakeReceiver = receiver
    }

    private fun stopWatchingForWake() {
        wakeReceiver?.let { context.unregisterReceiver(it) }
        wakeReceiver = null
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
     * opening guess and not the answer: the first can be a ±200m cell estimate
     * that settles nothing about a 100m circle, and accuracy converges over the
     * following seconds, which is why the dot in a maps app starts vague
     * and tightens onto the building. So keep listening until a fix is sharp
     * enough to decide, and stop the moment one is. Network location only, as
     * [watchNetworkLocation] explains: this runs at every registration and
     * every stale re-arm, which is too often to pay for GPS.
     *
     * When the window closes with nothing conclusive, commit on the sharpest
     * fix seen, judged on centre distance alone. Holding out for a fix whose
     * whole uncertainty circle clears the boundary sounds rigorous and is
     * unsatisfiable on plenty of real phones: one reporting ±300m indoors can
     * never prove itself inside a 100m circle, so the burst expired undecided
     * every time and place state sat empty for good — standing in the kitchen
     * with the microphone shut. A guess the geofencer corrects beats an answer
     * that never arrives, and a wrong one costs at most an hour: [refreshIfStale]
     * re-seeds once the fences go quiet.
     */
    @SuppressLint("MissingPermission") // gated on hasPermissions() in register()
    private fun seedFromCurrentLocation(places: List<ListeningPlace>) {
        stopSeed()

        var sharpest: Location? = null

        fun settle(inside: List<ListeningPlace>, location: Location, how: String) {
            Log.i(
                TAG,
                "Seeded from ${location.provider} ($how): " +
                    "inside ${inside.size} of ${places.size} place(s)",
            )
            insideIds.value = inside.map { it.id }.toSet()
            lastConfirmedAt = SystemClock.elapsedRealtime()
            publish()
            stopSeed()
        }

        /** Weigh one fix. True once the state is settled and no more are wanted. */
        fun offer(location: Location): Boolean {
            val best = sharpest
            if (best == null || location.accuracy < best.accuracy) sharpest = location

            val inside = places.filter { it.confidentlyContains(location) }
            // "Nowhere near any of them" has to be proved too, not assumed
            // from the absence of a positive: a ±99m fix rules you out of a
            // 100m circle exactly as poorly as it rules you into one.
            if (inside.isNotEmpty() || places.all { it.confidentlyExcludes(location) }) {
                settle(inside, location, "±${location.accuracy.toInt()}m")
                return true
            }
            Log.i(TAG, "Seed fix ±${location.accuracy.toInt()}m — too vague, waiting for a better one")
            return false
        }

        if (platformLastKnown()?.let(::offer) == true) return

        // The burst expires in silence, with no callback and no error, so the
        // last word has to be the backstop's or an inconclusive seed never ends.
        startPlatformSeed(::offer)
        seedBackstop = platformBackstop(places) { sharpest }
        seedHandler.postDelayed(seedBackstop!!, SEED_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission") // gated on hasPermissions() in arm()
    private fun startPlatformSeed(offer: (Location) -> Boolean) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            Log.w(TAG, "Network location is off, so there is nothing to seed from")
            return
        }
        val listener = LocationListener { location -> offer(location) }
        platformSeedListener = listener
        runCatching {
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, SEED_INTERVAL_MS, 0f, listener, Looper.getMainLooper(),
            )
        }.onFailure {
            Log.w(TAG, "Couldn't seed place state from network location", it)
            platformSeedListener = null
        }
    }

    private fun platformBackstop(
        places: List<ListeningPlace>,
        sharpest: () -> Location?,
    ) = Runnable {
        val settled = sharpest()
        if (settled == null) {
            Log.w(TAG, "Platform seed window closed with no usable fix — leaving it to the fences")
            stopSeed()
        } else {
            Log.i(
                TAG,
                "Seeded from ${settled.provider} (best of ${SEED_TIMEOUT_MS / 1000}s, " +
                    "±${settled.accuracy.toInt()}m): inside " +
                    "${places.count { it.contains(settled) }} of ${places.size} place(s)",
            )
            insideIds.value = places.filter { it.contains(settled) }.map { it.id }.toSet()
            lastConfirmedAt = SystemClock.elapsedRealtime()
            publish()
            stopSeed()
        }
    }

    /**
     * The sharpest fix the platform already has to hand, across every enabled
     * provider, ignoring anything too old to describe where we are now.
     *
     * Play Services is the right geofencer and is not always the best-informed
     * location source underneath it. Where GmsCore is sandboxed or cut off from
     * Google's backend, its fused provider drops to cell-tower grade — ±300m,
     * which settles nothing about a 100m circle — while the OS's own network
     * provider sits on a ±10m fix it will hand over for free. Asking both and
     * keeping the sharper costs one cache read and no power at all.
     *
     * Deliberately not shared with [dev.heyari.ari.location.LocationProvider],
     * which sweeps the same providers: that class promises skills a coarse fix
     * and checks only the coarse grant, where this one is gated on fine
     * location and wants every metre of it.
     */
    @SuppressLint("MissingPermission") // gated on hasPermissions() in arm()
    private fun platformLastKnown(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val oldestAccepted = SystemClock.elapsedRealtimeNanos() - SEED_MAX_AGE_MS * 1_000_000
        // Asked per-provider because one throwing must not cost us the others.
        return lm.getProviders(true)
            .mapNotNull { provider ->
                runCatching { lm.getLastKnownLocation(provider) }
                    .onFailure { e -> Log.w(TAG, "last-known from $provider unavailable", e) }
                    .getOrNull()
            }
            .filter { it.hasAccuracy() && it.elapsedRealtimeNanos >= oldestAccepted }
            .minByOrNull { it.accuracy }
    }

    private fun stopSeed() {
        seedBackstop?.let { seedHandler.removeCallbacks(it) }
        seedBackstop = null
        platformSeedListener?.let { listener ->
            runCatching { context.getSystemService(LocationManager::class.java)?.removeUpdates(listener) }
        }
        platformSeedListener = null
    }

    fun clear() {
        stopSeed()
        stopWatchingForWake()
        stopNetworkWatch()
        registered = emptyList()
        insideIds.value = emptySet()
        publish()
        if (!playServicesAvailable()) return
        client.removeGeofences(transitionPendingIntent())
            .addOnFailureListener { Log.w(TAG, "Failed to remove geofences", it) }
    }

    /**
     * A fix from [watchNetworkLocation], tested against every place.
     *
     * Treated as equal in authority to a Play Services transition: under [BOTH]
     * whichever stack notices first wins, and the other agreeing later is a
     * no-op on a set. A fix that lands squarely inside or outside a place also
     * outranks a seed still guessing, so it stops one.
     */
    internal fun onNetworkFix(intent: Intent) {
        val location = IntentCompat.getParcelableExtra(
            intent, LocationManager.KEY_LOCATION_CHANGED, Location::class.java,
        ) ?: return
        // Any fix at all proves the checks are still running, which is the only
        // question [refreshIfStale] is asking.
        lastConfirmedAt = SystemClock.elapsedRealtime()
        val before = insideIds.value
        val after = insideAfterFix(before, registered.map { it.reading(location) })
        if (after == before) return
        stopSeed()
        val names = registered.filter { it.id in after }.map { it.name }
        Log.i(TAG, "Network fix ±${location.accuracy.toInt()}m: inside ${names.joinToString().ifEmpty { "nowhere" }}")
        insideIds.value = after
        publish()
    }

    internal fun onTransition(event: GeofencingEvent) {
        if (event.hasError()) {
            Log.w(TAG, "Geofence event error code ${event.errorCode}")
            return
        }
        val ids = event.triggeringGeofences?.map { it.requestId }?.toSet() ?: return
        // Anything at all from the geofencer proves it is still watching, which
        // is the only question [refreshIfStale] is asking.
        lastConfirmedAt = SystemClock.elapsedRealtime()
        // The thing the seed was standing in for has spoken, and it is the
        // better authority — Play Services has been watching continuously,
        // where we get one burst. Let it go before a late fix overwrites this.
        stopSeed()
        // Logged at the same weight as the platform crossings so the two can be
        // read side by side. Under BOTH the interesting number is not that a
        // crossing arrived, it is which stack said it first and how far apart
        // they were.
        val names = ids.map { id -> registered.firstOrNull { it.id == id }?.name ?: id }
        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                Log.i(TAG, "Play fence: entered ${names.joinToString()}")
                insideIds.update { it + ids }
                publish()
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                Log.i(TAG, "Play fence: left ${names.joinToString()}")
                insideIds.update { it - ids }
                publish()
            }
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

    private fun networkPendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
            .setAction(GeofenceReceiver.ACTION_NETWORK_FIX)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_NETWORK,
            intent,
            // Mutable: the platform fills KEY_LOCATION_CHANGED in on delivery.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /**
     * The PendingIntent an old build registered a place's proximity alert with,
     * rebuilt exactly, since removing an alert takes an equal one.
     */
    private fun legacyProximityPendingIntent(placeId: String): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
            .setAction(LEGACY_ACTION_PROXIMITY)
            .setData("ari://place/$placeId".toUri())
        return PendingIntent.getBroadcast(
            context,
            REQUEST_PROXIMITY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private companion object {
        const val TAG = "PlaceGeofences"
        const val REQUEST_TRANSITION = 0
        const val REQUEST_PROXIMITY = 1
        const val REQUEST_NETWORK = 2
        const val LEGACY_ACTION_PROXIMITY = "dev.heyari.ari.PLACE_PROXIMITY"

        const val RESPONSIVENESS_MS = 5 * 60 * 1000

        // The Play fences' own slack, so neither stack is the slower to notice.
        const val WATCH_INTERVAL_MS = RESPONSIVENESS_MS.toLong()

        const val SEED_INTERVAL_MS = 1000L
        const val SEED_TIMEOUT_MS = 30 * 1000L

        // How stale a last-known fix may be and still be taken for where we
        // are. Generous next to the geofencer's own five minutes of slack,
        // because the alternative is not a fresher fix but a much vaguer one.
        const val SEED_MAX_AGE_MS = 15 * 60 * 1000L

        // Generous on purpose. Silence is normal — a stationary device inside
        // its own fence has nothing to report for a whole night — so this is
        // the point where silence stops being explainable, not a heartbeat.
        const val STALE_AFTER_MS = 60 * 60 * 1000L
    }
}

/**
 * The two answers the seed prefers, each requiring the fix's whole uncertainty
 * circle to fall on one side of the boundary.
 *
 * Ignoring accuracy reads a ±80m fix as if it were a survey peg, which is how a
 * phone sat in the kitchen gets ruled out of its own 100m home circle. So while
 * there is still time to get a sharper fix, anything these two can't settle
 * isn't taken for an answer — [ListeningPlace.contains] takes over once that
 * time is up.
 */
private fun ListeningPlace.confidentlyContains(location: Location): Boolean =
    distanceFrom(location) + location.accuracy <= radiusMetres

private fun ListeningPlace.confidentlyExcludes(location: Location): Boolean =
    distanceFrom(location) - location.accuracy > radiusMetres

/** A place as one fix sees it: how far off its centre the fix is, and how sure. */
internal data class PlaceReading(
    val id: String,
    val radiusMetres: Float,
    val distanceMetres: Float,
    val accuracyMetres: Float,
)

/**
 * Which places the device is inside after one fix. A place is entered when the
 * fix's whole uncertainty circle falls inside it and left when it falls wholly
 * outside; a fix that straddles the edge changes nothing, as a fence reports
 * nothing between crossings.
 */
internal fun insideAfterFix(inside: Set<String>, readings: List<PlaceReading>): Set<String> =
    readings.fold(inside) { ids, reading ->
        when {
            reading.distanceMetres + reading.accuracyMetres <= reading.radiusMetres -> ids + reading.id
            reading.distanceMetres - reading.accuracyMetres > reading.radiusMetres -> ids - reading.id
            else -> ids
        }
    }

private fun ListeningPlace.reading(location: Location) =
    PlaceReading(id, radiusMetres, distanceFrom(location), location.accuracy)

/** Centre distance alone: the seed's last word when accuracy won't converge. */
private fun ListeningPlace.contains(location: Location): Boolean =
    distanceFrom(location) <= radiusMetres

private fun ListeningPlace.distanceFrom(location: Location): Float {
    val metres = FloatArray(1)
    Location.distanceBetween(latitude, longitude, location.latitude, location.longitude, metres)
    return metres[0]
}

/** Sent by `:gms`, consumed by the main process. Never leaves the package. */
const val ACTION_PLACE_STATE = "dev.heyari.ari.PLACE_STATE"
const val EXTRA_PLACE_NAMES = "place_names"

@AndroidEntryPoint
class GeofenceReceiver : BroadcastReceiver() {

    @Inject
    lateinit var placeGeofences: PlaceGeofences

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_NETWORK_FIX -> placeGeofences.onNetworkFix(intent)
            else -> {
                val event = GeofencingEvent.fromIntent(intent) ?: return
                placeGeofences.onTransition(event)
            }
        }
    }

    companion object {
        const val ACTION_TRANSITION = "dev.heyari.ari.GEOFENCE_TRANSITION"
        const val ACTION_NETWORK_FIX = "dev.heyari.ari.PLACE_NETWORK_FIX"
    }
}
