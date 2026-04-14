package dev.heyari.ari.notifications

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
 * Plays a skill-declared alert: SOUND → speech → SOUND → speech → … on
 * loop until the user dismisses or the cap fires. Generic — knows nothing
 * about timers; the [AlertSpec] in the start intent declares everything
 * (sound source, speech text, urgency, cycle/duration caps, actions).
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
    private var tts: TextToSpeech? = null
    private var currentSpec: AlertSpec? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopLoopAndSelf(dismissNotification = true)
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val specJson = intent?.getStringExtra(EXTRA_ALERT_SPEC_JSON)
                val spec = specJson?.let { AlertSpecCodec.decode(it) } ?: run {
                    Log.w(TAG, "ACTION_START with no/malformed AlertSpec — stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                currentSpec = spec
                startForegroundWithAlert(spec, alerting = true)
                startLoop(spec)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithAlert(spec: AlertSpec, alerting: Boolean) {
        // Register before posting the notification so the FSN-launched
        // AlertActivity sees the id present when it starts collecting.
        if (alerting) AlertRegistry.start(spec.id)
        val notification = buildAlertNotification(this, spec, alerting)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId(spec.id),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(notificationId(spec.id), notification)
        }
    }

    private fun startLoop(spec: AlertSpec) {
        loopJob?.cancel()
        loopJob = scope.launch {
            val started = System.currentTimeMillis()
            var cycles = 0
            initMediaPlayer(spec)
            if (spec.speechLoop != null) initTts()

            while (shouldContinueAlerting(
                    cyclesCompleted = cycles,
                    elapsedMs = System.currentTimeMillis() - started,
                    maxCycles = spec.maxCycles,
                    maxDurationMs = spec.autoStopMs,
                )
            ) {
                playOnce()
                delay(GAP_BETWEEN_SOUND_AND_SPEECH_MS)
                spec.speechLoop?.let { speak(it) }
                delay(GAP_BETWEEN_CYCLES_MS)
                cycles++
            }

            // Cap reached naturally — drop to a quiet "tap to dismiss"
            // notification and stop. User can still open/dismiss it.
            startForegroundWithAlert(spec, alerting = false)
            releaseAudio()
            stopSelf()
        }
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

    private fun stopLoopAndSelf(dismissNotification: Boolean) {
        loopJob?.cancel()
        loopJob = null
        releaseAudio()
        val id = currentSpec?.id
        if (id != null) AlertRegistry.stop(id)
        if (dismissNotification && id != null) {
            getSystemService<NotificationManager>()?.cancel(notificationId(id))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseAudio() {
        runCatching {
            player?.setOnCompletionListener(null)
            player?.setOnErrorListener(null)
            player?.release()
        }
        player = null
        runCatching {
            tts?.setOnUtteranceProgressListener(null)
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
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
    // Tap-the-shade-entry target: opens the main app. Distinct from the
    // full-screen-intent target below, which goes to the dedicated
    // AlertActivity so the user lands on a Stop button instead of the
    // PIN keypad → main UI two-step.
    val contentIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
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
