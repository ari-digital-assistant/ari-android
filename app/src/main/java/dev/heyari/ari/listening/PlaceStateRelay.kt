package dev.heyari.ari.listening

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

private const val TAG = "PlaceStateRelay"

/**
 * The main process's view of where it is, as broadcast by `:gms`.
 *
 * The fences themselves run over there, out of reach of the nightly Play
 * Services teardown. This is the only thing that crosses back, and it crosses
 * as a broadcast precisely because a broadcast creates no dependency: `:gms`
 * can die at 00:20 with the rest of the Play Services stack and the process
 * holding the microphone will not notice.
 *
 * Opens with an empty list rather than waiting. Somewhere-unknown is the
 * honest state until a fence or a seed says otherwise, and it is the same
 * state the in-process version started from.
 */
fun placeStateFlow(context: Context): Flow<List<String>> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            val names = intent?.getStringArrayListExtra(EXTRA_PLACE_NAMES) ?: return
            Log.i(TAG, "Place state from :gms — inside ${names.size} place(s)")
            trySend(names.toList())
        }
    }
    ContextCompat.registerReceiver(
        context,
        receiver,
        IntentFilter(ACTION_PLACE_STATE),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    send(emptyList())
    awaitClose { runCatching { context.unregisterReceiver(receiver) } }
}.distinctUntilChanged()
