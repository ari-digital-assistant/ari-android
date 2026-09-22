package dev.heyari.ari.listening

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Owns [PlaceGeofences], in the `:gms` process.
 *
 * Everything that touches Play Services lives on the far side of a process
 * boundary, because on GrapheneOS the whole Play Services stack tears itself
 * down nightly and takes every process holding gmscompat's RpcProvider with
 * it. Observed at 00:20 five nights running. Before this, that was Ari's main
 * process and the microphone went with it.
 *
 * Deliberately a started service and not a bound one. A binding would keep
 * `:gms` alive for no reason: the fences are registered as PendingIntents, so
 * the platform wakes this process when a crossing happens whether anything is
 * holding it or not. Deliberately not a ContentProvider either, which is the
 * trap this whole change exists to avoid: a provider hosted here would make
 * the main process a dependent of `:gms`, and the nightly cascade would kill
 * it exactly as it does today.
 *
 * State travels back by broadcast, from [PlaceGeofences.publish].
 */
@AndroidEntryPoint
class PlaceFenceService : Service() {

    @Inject
    lateinit var placeGeofences: PlaceGeofences

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REGISTER -> {
                val places = decodePlaces(intent.getStringExtra(EXTRA_PLACES))
                val source = PlaceFenceSource.fromSlug(
                    intent.getStringExtra(EXTRA_SOURCE),
                    PlaceFenceSource.PLAY,
                )
                Log.i(TAG, "Registering ${places.size} place(s) with $source fences")
                placeGeofences.register(places, source)
            }
            ACTION_CLEAR -> {
                Log.i(TAG, "Clearing fences")
                placeGeofences.clear()
                stopSelf()
            }
        }
        // The fences outlive this process, so there is nothing to restore on a
        // sticky restart. The main process re-registers when it needs to.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PlaceFenceService"

        private const val ACTION_REGISTER = "dev.heyari.ari.FENCES_REGISTER"
        private const val ACTION_CLEAR = "dev.heyari.ari.FENCES_CLEAR"
        private const val EXTRA_PLACES = "places"
        private const val EXTRA_SOURCE = "source"

        /**
         * Places cross as their own JSON rather than as Parcelables, reusing
         * the encoder the settings store already has. `:gms` must never open
         * the DataStore itself: it is not multi-process safe, and two
         * processes reading the same file is a corruption waiting to happen.
         */
        fun register(context: Context, places: List<ListeningPlace>, source: PlaceFenceSource) {
            context.startService(
                Intent(context, PlaceFenceService::class.java)
                    .setAction(ACTION_REGISTER)
                    .putExtra(EXTRA_PLACES, encodePlaces(places))
                    .putExtra(EXTRA_SOURCE, source.slug)
            )
        }

        fun clear(context: Context) {
            context.startService(
                Intent(context, PlaceFenceService::class.java).setAction(ACTION_CLEAR)
            )
        }
    }
}
