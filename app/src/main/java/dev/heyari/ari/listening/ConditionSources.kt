package dev.heyari.ari.listening

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * The live signals behind [ListeningCondition]. Each is a `callbackFlow`, so
 * registration and teardown ride the collector's lifecycle — nothing is
 * registered for a condition the user hasn't ticked, and nothing outlives the
 * service that collects it.
 */

/** Screen on, locked or not: what matters is whether the display is awake. */
internal fun screenOnFlow(context: Context): Flow<Boolean> = callbackFlow {
    val powerManager = context.getSystemService(PowerManager::class.java)
    trySend(powerManager.isInteractive)

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            trySend(intent?.action == Intent.ACTION_SCREEN_ON)
        }
    }
    // SCREEN_ON/OFF are protected system broadcasts and cannot be declared in
    // the manifest — runtime registration is the only way to hear them.
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    awaitClose { context.unregisterReceiver(receiver) }
}.distinctUntilChanged()

/**
 * Plugged into anything — mains, USB or a wireless pad. Deliberately not an
 * `ACTION_BATTERY_CHANGED` receiver: that fires every time the level or
 * temperature twitches, and burning wake-ups to save battery would be a joke.
 * The sticky read seeds the initial value; the two edge broadcasts carry it
 * from there.
 */
internal fun chargingFlow(context: Context): Flow<Boolean> = callbackFlow {
    trySend(isPluggedIn(context))

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            trySend(intent?.action == Intent.ACTION_POWER_CONNECTED)
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    awaitClose { context.unregisterReceiver(receiver) }
}.distinctUntilChanged()

private fun isPluggedIn(context: Context): Boolean {
    val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    return (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
}

/**
 * Any wearable audio device attached, by any transport.
 *
 * One `AudioDeviceCallback` rather than `ACTION_HEADSET_PLUG` plus a fistful of
 * Bluetooth intents: it covers wired, USB, BT, BLE and hearing aids through a
 * single platform API, needs no permission, and won't need revisiting when the
 * next transport ships.
 */
internal fun headsetFlow(context: Context): Flow<Boolean> = callbackFlow {
    val audioManager = context.getSystemService(AudioManager::class.java)

    fun emitCurrent() {
        val connected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { isWearableAudioDevice(it.type) }
        trySend(connected)
    }

    val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = emitCurrent()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = emitCurrent()
    }
    audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    emitCurrent()
    awaitClose { audioManager.unregisterAudioDeviceCallback(callback) }
}.distinctUntilChanged()

/**
 * Whether the wall clock currently sits inside one of [schedules].
 *
 * Recomputed on three triggers: a boundary alarm firing, the user editing the
 * clock or timezone, and the flow starting. Each recomputation also arms the
 * next boundary, so the alarm chain repairs itself after a process death — the
 * first tick on restart re-arms whatever was missed.
 */
internal fun scheduleWindowFlow(
    context: Context,
    schedules: List<ListeningSchedule>,
    alarms: ScheduleAlarms,
): Flow<Boolean> = callbackFlow {
    fun tick() {
        val now = LocalDateTime.now()
        trySend(isWithinAnySchedule(now, schedules))
        alarms.armNext(nextBoundaryAfter(now, schedules))
    }
    tick()

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) = tick()
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
        addAction(Intent.ACTION_DATE_CHANGED)
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

    val alarmJob = launch { alarms.boundaries.collect { tick() } }

    awaitClose {
        alarmJob.cancel()
        context.unregisterReceiver(receiver)
        alarms.cancel()
    }
}.distinctUntilChanged()

/**
 * Device types that mean "the user is wearing or riding in something they'd
 * talk to Ari through". A2DP is in on purpose — a car stereo is exactly the
 * moment you want hands-free, and excluding it because it has no microphone
 * would miss the point: Ari listens on the phone's mic either way.
 *
 * `TYPE_BLE_HEADSET` (API 31) and `TYPE_HEARING_AID` (API 28) are compile-time
 * constants inlined into this comparison, so they're safe on minSdk 29 — an
 * older device simply never reports them.
 */
internal fun isWearableAudioDevice(type: Int): Boolean = when (type) {
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_HEARING_AID,
    -> true

    else -> false
}
