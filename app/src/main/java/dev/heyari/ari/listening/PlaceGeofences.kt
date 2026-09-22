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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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

    private val seedHandler = Handler(Looper.getMainLooper())

    private var seedCallback: LocationCallback? = null

    private var seedBackstop: Runnable? = null

    private var wakeReceiver: BroadcastReceiver? = null

    // The platform seed's own listener, which has no Play Services equivalent
    // to cancel through. Kept so [stopSeed] can shut the burst down whichever
    // half of the code opened it.
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

        if (usePlatform) armPlatformFences(places)
        if (!usePlay) {
            seedFromCurrentLocation(places, useFused = false)
            return
        }

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

        seedFromCurrentLocation(places, useFused = true)
    }

    /**
     * The platform's own circles, registered alongside the Play Services ones
     * rather than instead of them.
     *
     * `addProximityAlert` is the cruder mechanism — no dwell, no batching, and
     * scheduling we don't get to tune — but it is watching a different stack,
     * so the two fail at different times. Where Play Services is sandboxed and
     * working off a ±300m fix, the crude fence on a ±10m platform fix is
     * routinely the one that notices.
     *
     * Re-registering the same PendingIntent replaces the alert rather than
     * doubling it, so a re-arm needs no teardown first.
     */
    @SuppressLint("MissingPermission") // gated on hasPermissions() in arm()
    private fun armPlatformFences(places: List<ListeningPlace>) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        for (place in places) {
            runCatching {
                lm.addProximityAlert(
                    place.latitude,
                    place.longitude,
                    place.radiusMetres,
                    NEVER_EXPIRE,
                    proximityPendingIntent(place.id),
                )
            }.onFailure { Log.w(TAG, "Failed to register platform fence for ${place.name}", it) }
        }
        Log.i(TAG, "Registered ${places.size} platform fence(s)")
    }

    private fun clearPlatformFences(places: List<ListeningPlace>) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        for (place in places) {
            runCatching { lm.removeProximityAlert(proximityPendingIntent(place.id)) }
                .onFailure { Log.w(TAG, "Failed to remove platform fence for ${place.name}", it) }
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
     * opening guess and not the answer: `getCurrentLocation` returns as soon as
     * anything satisfies the priority, which indoors is a ±200m cell-and-wifi
     * estimate that settles nothing about a 100m circle. Accuracy converges over
     * the following seconds — the same reason the dot in a maps app starts vague
     * and tightens onto the building. So keep listening until a fix is sharp
     * enough to decide, and stop the moment one is. Costly for a few seconds,
     * once per registration, against a feature that otherwise holds a
     * microphone open all day.
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
    private fun seedFromCurrentLocation(places: List<ListeningPlace>, useFused: Boolean) {
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

        if (!useFused) {
            // No Play Services to burst from, so ask the platform directly. The
            // backstop below is shared: it does not care which stack failed to
            // converge, only that the window closed.
            startPlatformSeed(::offer)
            seedBackstop = platformBackstop(places) { sharpest }
            seedHandler.postDelayed(seedBackstop!!, SEED_TIMEOUT_MS)
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, SEED_INTERVAL_MS)
            .setMinUpdateIntervalMillis(SEED_INTERVAL_MS)
            // Expires itself, so a seed that never converges can't leave the
            // GPS running behind us.
            .setDurationMillis(SEED_TIMEOUT_MS)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(::offer)
            }
        }
        seedCallback = callback
        locationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { Log.w(TAG, "Couldn't seed place state from current location", it) }

        // The burst expires in silence — no callback, no error — so the last
        // word has to be ours or an inconclusive seed never ends.
        val backstop = Runnable {
            val settled = sharpest
            if (settled == null) {
                // Deliberately no [lastConfirmedAt] stamp: nothing confirmed
                // anything, so the state stays stale and the next screen-on
                // re-arms rather than sitting out the hour on a lie.
                Log.w(TAG, "Seed window closed with no usable fix — leaving it to the geofencer")
                stopSeed()
            } else {
                settle(
                    places.filter { it.contains(settled) },
                    settled,
                    "best of ${SEED_TIMEOUT_MS / 1000}s, ±${settled.accuracy.toInt()}m",
                )
            }
        }
        seedBackstop = backstop
        seedHandler.postDelayed(backstop, SEED_TIMEOUT_MS)
    }

    /**
     * The same burst as the Play Services one, asked of the platform instead.
     * Every fix goes through the identical [offer] test, so the two paths settle
     * on the same evidence and differ only in who supplied it.
     */
    @SuppressLint("MissingPermission") // gated on hasPermissions() in arm()
    private fun startPlatformSeed(offer: (Location) -> Boolean) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val provider = bestPlatformProvider(lm) ?: run {
            Log.w(TAG, "No platform location provider enabled — nothing to seed from")
            return
        }
        val listener = LocationListener { location -> offer(location) }
        platformSeedListener = listener
        runCatching {
            lm.requestLocationUpdates(provider, SEED_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
        }.onFailure {
            Log.w(TAG, "Couldn't seed place state from $provider", it)
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
     * The platform's fused provider where there is one, which is the closest
     * thing it has to the Play Services client, and the network provider
     * otherwise. GPS last: indoors it is the one that never answers.
     */
    private fun bestPlatformProvider(lm: LocationManager): String? {
        val enabled = lm.getProviders(true)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                LocationManager.FUSED_PROVIDER in enabled -> LocationManager.FUSED_PROVIDER
            LocationManager.NETWORK_PROVIDER in enabled -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in enabled -> LocationManager.GPS_PROVIDER
            else -> null
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
        seedCallback?.let { locationClient.removeLocationUpdates(it) }
        seedCallback = null
        platformSeedListener?.let { listener ->
            runCatching { context.getSystemService(LocationManager::class.java)?.removeUpdates(listener) }
        }
        platformSeedListener = null
    }

    fun clear() {
        stopSeed()
        stopWatchingForWake()
        // Read before blanking: the platform alerts can only be removed by
        // rebuilding the PendingIntent each one was registered with, which
        // needs the ids.
        val wasRegistered = registered
        registered = emptyList()
        insideIds.value = emptySet()
        publish()
        if (wasRegistered.isNotEmpty()) clearPlatformFences(wasRegistered)
        if (!playServicesAvailable()) return
        client.removeGeofences(transitionPendingIntent())
            .addOnFailureListener { Log.w(TAG, "Failed to remove geofences", it) }
    }

    /**
     * A platform proximity crossing, which carries one place and a direction
     * rather than Play Services' batch.
     *
     * Treated as equal in authority to a Play Services transition: under [BOTH]
     * whichever stack notices first wins, and the other agreeing later is a
     * no-op on a set. [stopSeed] for the same reason the Play path does it — a
     * fence that has actually spoken outranks a burst still guessing.
     */
    internal fun onProximity(intent: Intent) {
        val placeId = intent.data?.lastPathSegment ?: return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        val name = registered.firstOrNull { it.id == placeId }?.name ?: placeId
        Log.i(TAG, "Platform fence: ${if (entering) "entered" else "left"} $name")
        lastConfirmedAt = SystemClock.elapsedRealtime()
        stopSeed()
        if (entering) insideIds.update { it + placeId } else insideIds.update { it - placeId }
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

    /**
     * One alert per place, told apart by the intent's data.
     *
     * PendingIntents are deduped on action and data and explicitly *not* on
     * extras, so putting the id in an extra would collapse every place onto a
     * single fence — the last one registered silently replacing the rest.
     */
    private fun proximityPendingIntent(placeId: String): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
            .setAction(GeofenceReceiver.ACTION_PROXIMITY)
            .setData("ari://place/$placeId".toUri())
        return PendingIntent.getBroadcast(
            context,
            REQUEST_PROXIMITY,
            intent,
            // Mutable: the platform fills KEY_PROXIMITY_ENTERING in on delivery.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private companion object {
        const val TAG = "PlaceGeofences"
        const val REQUEST_TRANSITION = 0
        const val REQUEST_PROXIMITY = 1

        /** `addProximityAlert`'s own spelling of Geofence.NEVER_EXPIRE. */
        const val NEVER_EXPIRE = -1L
        const val RESPONSIVENESS_MS = 5 * 60 * 1000

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
            ACTION_PROXIMITY -> placeGeofences.onProximity(intent)
            else -> {
                val event = GeofencingEvent.fromIntent(intent) ?: return
                placeGeofences.onTransition(event)
            }
        }
    }

    companion object {
        const val ACTION_TRANSITION = "dev.heyari.ari.GEOFENCE_TRANSITION"
        const val ACTION_PROXIMITY = "dev.heyari.ari.PLACE_PROXIMITY"
    }
}
