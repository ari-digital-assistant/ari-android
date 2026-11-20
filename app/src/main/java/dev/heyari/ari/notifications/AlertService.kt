package dev.heyari.ari.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import dev.heyari.ari.MainActivity
import dev.heyari.ari.R
import dev.heyari.ari.assets.AssetResolver
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Plays skill-declared alerts: SOUND → speech → SOUND → speech → … on
 * loop until the user dismisses or the cap fires. Generic — knows nothing
 * about timers; the [AlertSpec] in the start intent declares everything
 * (sound source, speech text, urgency, cycle/duration caps, actions).
 *
 * Several can ring at once, as two timers finishing together do. They share
 * one loop, the way a clock app rings: the newest alert's sound, then each
 * one's speech in turn. Each keeps its own notification, Stop and caps, and
 * stopping one leaves the others ringing.
 *
 * Foreground service (`mediaPlayback`) so audio survives screen-off and
 * backgrounding. Owns its own [MediaPlayer] and a dedicated [TextToSpeech]
 * instance — not the shared [dev.heyari.ari.tts.SpeechOutput] singleton —
 * because alert audio routes through `USAGE_ALARM` AudioAttributes and
 * swapping attributes on the shared instance races with conversational TTS.
 */
@AndroidEntryPoint
class AlertService : Service() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var assetResolver: AssetResolver

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null
    private var player: MediaPlayer? = null
    /** The skill and sound token [player] was built for. */
    private var playerSound: Pair<String, String>? = null
    private var tts: TextToSpeech? = null

    /** Ringing alerts by id, oldest first. */
    private val ringing = LinkedHashMap<String, Ringing>()

    /**
     * The alert whose notification the foreground service runs under. Android
     * takes exactly one; the other alerts' notifications are posted beside it.
     */
    private var foregroundId: String? = null

    private class Ringing(val spec: AlertSpec, val startedAt: Long) {
        var cycles = 0
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val id = intent.getStringExtra(EXTRA_ALERT_ID)
                if (id != null) stopAlert(id) else ringing.keys.toList().forEach(::stopAlert)
                // A stop for an alert that had already ended starts the
                // service with nothing to ring.
                if (ringing.isEmpty()) stopSelf()
            }
            ACTION_START, null -> {
                val specJson = intent?.getStringExtra(EXTRA_ALERT_SPEC_JSON)
                val spec = specJson?.let { AlertSpecCodec.decode(it) }
                if (spec == null) {
                    Log.w(TAG, "ACTION_START with no/malformed AlertSpec, ignoring")
                    if (ringing.isEmpty()) stopSelf()
                } else {
                    startAlert(spec)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startAlert(spec: AlertSpec) {
        // Register before posting the notification so the FSN-launched
        // AlertActivity sees the id present when it starts collecting.
        AlertRegistry.start(spec)
        ringing.remove(spec.id)
        ringing[spec.id] = Ringing(spec, System.currentTimeMillis())
        val notification = buildAlertNotification(this, spec, alerting = true)
        if (foregroundId == null || foregroundId == spec.id) {
            postForeground(spec.id, notification)
        } else {
            // Beside the foreground one, not in its place: moving the service
            // to a new id makes Android cancel the old notification, and the
            // first timer lost its Stop when the second went off.
            getSystemService<NotificationManager>()?.notify(notificationId(spec.id), notification)
            // Every startForegroundService call is owed a startForeground.
            // Re-posting the current one unchanged pays it without a new alert.
            ringing[foregroundId]?.let {
                postForeground(it.spec.id, buildAlertNotification(this, it.spec, alerting = true))
            }
        }
        if (loopJob?.isActive != true) startLoop()
    }

    private fun postForeground(alertId: String, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId(alertId),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(notificationId(alertId), notification)
        }
        foregroundId = alertId
    }

    /**
     * One loop for every ringing alert: the newest one's sound, then each
     * one's speech in turn. Alerts that start meanwhile join at the next
     * round. Ends once the last alert has reached its cap.
     */
    private fun startLoop() {
        loopJob = scope.launch {
            while (true) {
                endExpired()
                val round = ringing.values.toList()
                val newest = round.lastOrNull() ?: break
                prepareSound(newest.spec)
                if (tts == null && round.any { it.spec.speechLoop != null }) initTts()

                playOnce()
                delay(GAP_BETWEEN_SOUND_AND_SPEECH_MS)
                for (alert in round) {
                    // Skip one that was stopped while an earlier one spoke.
                    if (alert.spec.id in ringing) alert.spec.speechLoop?.let { speak(it) }
                }
                delay(GAP_BETWEEN_CYCLES_MS)
                round.forEach { it.cycles++ }
            }
            releaseAudio()
            stopSelf()
        }
    }

    /**
     * Alerts past their own caps go quiet: they stop ringing and their
     * notification drops to "Tap to dismiss".
     */
    private fun endExpired() {
        val now = System.currentTimeMillis()
        for (alert in ringing.values.toList()) {
            if (shouldContinueAlerting(
                    cyclesCompleted = alert.cycles,
                    elapsedMs = now - alert.startedAt,
                    maxCycles = alert.spec.maxCycles,
                    maxDurationMs = alert.spec.autoStopMs,
                )
            ) {
                continue
            }
            release(alert.spec.id)
            val notifications = getSystemService<NotificationManager>() ?: continue
            notifications.cancel(notificationId(alert.spec.id))
            notifications.notify(
                endedNotificationId(alert.spec.id),
                buildAlertNotification(this, alert.spec, alerting = false),
            )
        }
    }

    /** The user stopped [id]. */
    private fun stopAlert(id: String) {
        release(id)
        // Cancelled even if [id] wasn't ringing, so a stop for an alert that
        // already ended still clears what it left behind.
        getSystemService<NotificationManager>()?.run {
            cancel(notificationId(id))
            cancel(endedNotificationId(id))
        }
        if (ringing.isEmpty()) {
            loopJob?.cancel()
            loopJob = null
            releaseAudio()
        }
    }

    /**
     * Takes [id] out of the ringing set. If the foreground service runs under
     * its notification, the service moves to another ringing alert's or
     * leaves the foreground, and Android takes that notification down either
     * way. The caller cancels the notification of an alert that wasn't the
     * foreground one.
     */
    private fun release(id: String) {
        ringing.remove(id)
        AlertRegistry.stop(id)
        if (id != foregroundId) return
        val next = ringing.values.lastOrNull()
        if (next != null) {
            postForeground(next.spec.id, buildAlertNotification(this, next.spec, alerting = true))
        } else {
            // REMOVE, not DETACH and cancel: a detached notification keeps the
            // service's mark for a moment, a cancel sent in that moment is
            // dropped, and "Alerting…" stayed in the shade after Stop.
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundId = null
        }
    }

    /** Points [player] at [spec]'s sound, rebuilding it only when the sound changes. */
    private fun prepareSound(spec: AlertSpec) {
        val sound = spec.skillId to spec.sound
        if (sound == playerSound) return
        releasePlayer()
        playerSound = sound
        initMediaPlayer(spec)
    }

    private fun initMediaPlayer(spec: AlertSpec) {
        val uri = resolveSound(spec) ?: run {
            Log.w(TAG, "no sound URI for ${spec.sound}; alert will be speech-only")
            return
        }
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        runCatching {
            mp.setDataSource(this, uri)
            mp.prepare()
        }.onFailure {
            Log.w(TAG, "MediaPlayer prepare failed for $uri", it)
            runCatching { mp.release() }
            return
        }
        player = mp
    }

    /**
     * Resolve the spec's `sound` field to a playable Uri. Tokens map to
     * platform default URIs; `asset:<path>` resolves against the emitting
     * skill's bundle dir. Unknown tokens or missing assets fall through to
     * `system.notification` so the user still hears something.
     */
    private fun resolveSound(spec: AlertSpec): Uri? {
        if (spec.sound.startsWith(AlertSpec.SoundToken.ASSET_PREFIX)) {
            assetResolver.uri(spec.skillId, spec.sound)?.let { return it }
            Log.w(TAG, "asset ${spec.sound} unresolved for skill ${spec.skillId} — falling back")
        }
        val ringtoneType = when (spec.sound) {
            AlertSpec.SoundToken.ALARM -> RingtoneManager.TYPE_ALARM
            AlertSpec.SoundToken.SILENT -> return null
            AlertSpec.SoundToken.NOTIFICATION -> RingtoneManager.TYPE_NOTIFICATION
            else -> RingtoneManager.TYPE_NOTIFICATION
        }
        return RingtoneManager.getDefaultUri(ringtoneType)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    private suspend fun initTts() = suspendCancellableCoroutine<Unit> { cont ->
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS init failed with status $status — alert will be sound-only")
                if (cont.isActive) cont.resume(Unit)
                return@TextToSpeech
            }
            // Route TTS through the alarm stream so it bypasses DND alongside
            // the MediaPlayer. CONTENT_TYPE_SPEECH (not SONIFICATION) tells
            // the audio framework this is intelligible speech so voice
            // enhancement on capable devices kicks in.
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            scope.launch {
                runCatching {
                    val voiceName = settingsRepository.activeTtsVoice.first()
                    if (voiceName != null) {
                        engine.voices?.firstOrNull { it.name == voiceName }
                            ?.let { engine.voice = it }
                    }
                }
                if (cont.isActive) cont.resume(Unit)
            }
        }
        tts = engine
    }

    private suspend fun playOnce() = suspendCancellableCoroutine<Unit> { cont ->
        val mp = player ?: run { cont.resume(Unit); return@suspendCancellableCoroutine }
        mp.setOnCompletionListener {
            it.seekTo(0)
            if (cont.isActive) cont.resume(Unit)
        }
        mp.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "MediaPlayer error what=$what extra=$extra — continuing loop")
            if (cont.isActive) cont.resume(Unit)
            true
        }
        runCatching { mp.start() }.onFailure {
            Log.w(TAG, "MediaPlayer.start failed", it)
            if (cont.isActive) cont.resume(Unit)
        }
    }

    private suspend fun speak(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        val engine = tts ?: run { cont.resume(Unit); return@suspendCancellableCoroutine }
        val utteranceId = "alert-${System.nanoTime()}"
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId && cont.isActive) cont.resume(Unit)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId && cont.isActive) cont.resume(Unit)
            }
            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId && cont.isActive) cont.resume(Unit)
            }
        })
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val rc = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (rc != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
    }

    private fun releaseAudio() {
        releasePlayer()
        runCatching {
            tts?.setOnUtteranceProgressListener(null)
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }

    private fun releasePlayer() {
        runCatching {
            player?.setOnCompletionListener(null)
            player?.setOnErrorListener(null)
            player?.release()
        }
        player = null
        playerSound = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        releaseAudio()
    }

    companion object {
        const val ACTION_START = "dev.heyari.ari.ALERT_START"
        const val ACTION_STOP = "dev.heyari.ari.ALERT_STOP"
        const val EXTRA_ALERT_SPEC_JSON = "alert_spec_json"
        const val EXTRA_ALERT_ID = "alert_id"
        /** On a [MainActivity] intent: stop this alert as the app opens. */
        const val EXTRA_STOP_ALERT_ID = "stop_alert_id"

        private const val TAG = "AlertService"
        private const val GAP_BETWEEN_SOUND_AND_SPEECH_MS = 150L
        private const val GAP_BETWEEN_CYCLES_MS = 900L

        fun startIntent(context: Context, spec: AlertSpec): Intent =
            Intent(context, AlertService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ALERT_SPEC_JSON, AlertSpecCodec.encode(spec))

        fun stopIntent(context: Context, id: String): Intent =
            Intent(context, AlertService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_ALERT_ID, id)

        fun notificationId(alertId: String): Int =
            (alertId.hashCode() xor 0x71_4d_00_02) and 0x7FFFFFFF

        /**
         * The quiet "Tap to dismiss" left when an alert reaches its cap. Its
         * own id, so it is never the service's notification and leaving the
         * foreground can't take it down or leave the ringing one behind.
         */
        fun endedNotificationId(alertId: String): Int =
            (alertId.hashCode() xor 0x71_4d_00_03) and 0x7FFFFFFF
    }
}

/**
 * Cycle/duration cap predicate. Pure — extracted as top-level for
 * unit testability and so callers can verify cap-from-spec behaviour
 * without standing up the service.
 */
internal fun shouldContinueAlerting(
    cyclesCompleted: Int,
    elapsedMs: Long,
    maxCycles: Int,
    maxDurationMs: Long,
): Boolean = cyclesCompleted < maxCycles && elapsedMs < maxDurationMs

/**
 * Builds the alert notification the service posts via `startForeground`.
 * Spec-driven: urgency / fullTakeover / actions all come from the AlertSpec.
 * [alerting] true while the loop is running (shows skill-declared actions);
 * false after the cap is reached (shows only "Tap to dismiss").
 */
internal fun buildAlertNotification(
    context: Context,
    spec: AlertSpec,
    alerting: Boolean,
): android.app.Notification {
    // Tap-the-shade-entry target: opens the main app, and while ringing
    // also silences the alert, because people tap the notification
    // expecting that. Distinct from the full-screen-intent
    // target below, which goes to the dedicated AlertActivity so the user
    // lands on a Stop button instead of the PIN keypad → main UI two-step.
    val openApp = Intent(context, MainActivity::class.java).addFlags(
        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
    )
    if (alerting) openApp.putExtra(AlertService.EXTRA_STOP_ALERT_ID, spec.id)
    val contentIntent = PendingIntent.getActivity(
        context,
        // Per-alert while ringing so two alerts sounding at once each
        // carry their own id instead of sharing one PendingIntent's extras.
        if (alerting) AlertService.notificationId(spec.id) else 0,
        openApp,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val takeoverIntent = PendingIntent.getActivity(
        context,
        spec.id.hashCode(),
        AlertActivity.intent(context, spec),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val priority = when (spec.urgency) {
        AlertSpec.Urgency.NORMAL -> NotificationCompat.PRIORITY_DEFAULT
        AlertSpec.Urgency.HIGH -> NotificationCompat.PRIORITY_HIGH
        AlertSpec.Urgency.CRITICAL -> NotificationCompat.PRIORITY_MAX
    }
    val builder = NotificationCompat.Builder(context, NotificationChannels.ALERT)
        .setSmallIcon(R.drawable.ic_ari_symbolic)
        .setContentTitle(spec.title)
        .setContentText(spec.body ?: if (alerting) "Alerting…" else "Tap to dismiss")
        .setAutoCancel(!alerting)
        .setOngoing(alerting)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setPriority(priority)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentIntent)

    // Full-takeover heads-up + lockscreen full-screen, gated on critical
    // urgency as a safety check (skills can't get a takeover on a normal
    // alert by accident).
    if (alerting && spec.fullTakeover && spec.urgency == AlertSpec.Urgency.CRITICAL) {
        builder.setFullScreenIntent(takeoverIntent, true)
    }

    if (alerting) {
        for (action in spec.actions) {
            val pi = pendingIntentFor(context, spec, action)
            builder.addAction(0, action.label, pi)
        }
    }
    return builder.build()
}

private fun pendingIntentFor(context: Context, spec: AlertSpec, action: AlertAction): PendingIntent {
    return when (action.id) {
        "stop_alert" -> PendingIntent.getService(
            context,
            (spec.id + action.id).hashCode(),
            AlertService.stopIntent(context, spec.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        else -> {
            // Generic action — route via NotificationActionReceiver so the
            // utterance flows through engine.processInput.
            val intent = dev.heyari.ari.actions.NotificationActionReceiver.intent(
                context = context,
                actionId = action.id,
                utterance = action.utterance,
                alertIdToStop = spec.id,
            )
            PendingIntent.getBroadcast(
                context,
                (spec.id + action.id).hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
