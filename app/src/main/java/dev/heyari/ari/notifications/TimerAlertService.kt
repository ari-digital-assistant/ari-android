package dev.heyari.ari.notifications

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
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
 * Plays the Siri-style alert loop when a timer fires: SOUND → spoken name →
 * SOUND → spoken name → … on repeat until the user taps Stop or the cap
 * is reached. For anonymous timers the speech step is skipped so the cadence
 * stays the same.
 *
 * Runs as a foreground service (`mediaPlayback`) so the audio survives
 * screen-off and backgrounding. Owns its own [MediaPlayer] and a dedicated
 * [TextToSpeech] instance — not the shared [dev.heyari.ari.tts.SpeechOutput]
 * singleton — because alert audio needs `USAGE_ALARM` AudioAttributes and
 * swapping attributes on the shared instance races with conversational TTS.
 */
@AndroidEntryPoint
class TimerAlertService : Service() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var currentTimerId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopLoopAndSelf(dismissNotification = true)
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val id = intent?.getStringExtra(EXTRA_TIMER_ID) ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val name = intent.getStringExtra(EXTRA_TIMER_NAME)
                currentTimerId = id
                startForegroundWithAlert(id, name, alerting = true)
                startLoop(id, name)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithAlert(id: String, name: String?, alerting: Boolean) {
        val notification = buildAlertNotification(this, id, name, alerting)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId(id),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(notificationId(id), notification)
        }
    }

    private fun startLoop(id: String, name: String?) {
        loopJob?.cancel()
        loopJob = scope.launch {
            val started = System.currentTimeMillis()
            var cycles = 0
            initMediaPlayer()
            if (name != null) initTts()

            while (shouldContinueAlerting(
                    cyclesCompleted = cycles,
                    elapsedMs = System.currentTimeMillis() - started,
                )
            ) {
                playOnce()
                delay(GAP_BETWEEN_SOUND_AND_SPEECH_MS)
                if (name != null) {
                    speak("${capitaliseFirst(name)} timer")
                }
                delay(GAP_BETWEEN_CYCLES_MS)
                cycles++
            }

            // Cap reached naturally — drop to a quiet "tap to dismiss"
            // notification and stop. User can still open/dismiss it.
            startForegroundWithAlert(id, name, alerting = false)
            releaseAudio()
            stopSelf()
        }
    }

    private fun initMediaPlayer() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (uri == null) {
            Log.w(TAG, "no default alarm/notification URI available — skipping sound")
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
            Log.w(TAG, "MediaPlayer prepare failed", it)
            runCatching { mp.release() }
            return
        }
        player = mp
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
        val utteranceId = "timer-alert-${System.nanoTime()}"
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
        val id = currentTimerId
        if (dismissNotification && id != null) {
            getSystemService<android.app.NotificationManager>()?.cancel(notificationId(id))
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
        const val ACTION_START = "dev.heyari.ari.TIMER_ALERT_START"
        const val ACTION_STOP = "dev.heyari.ari.TIMER_ALERT_STOP"
        const val EXTRA_TIMER_ID = "timer_id"
        const val EXTRA_TIMER_NAME = "timer_name"

        private const val TAG = "TimerAlertService"
        private const val GAP_BETWEEN_SOUND_AND_SPEECH_MS = 150L
        private const val GAP_BETWEEN_CYCLES_MS = 900L

        fun startIntent(context: Context, id: String, name: String?): Intent =
            Intent(context, TimerAlertService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TIMER_ID, id)
                .putExtra(EXTRA_TIMER_NAME, name)

        fun stopIntent(context: Context, id: String): Intent =
            Intent(context, TimerAlertService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_TIMER_ID, id)

        fun notificationId(timerId: String): Int =
            (timerId.hashCode() xor 0x71_4d_00_02) and 0x7FFFFFFF
    }
}

/**
 * Pure cadence-cap predicate — extracted as top-level for unit testability.
 * Returns `true` while the loop should keep cycling, `false` when either the
 * cycle count or the elapsed duration cap has been reached.
 */
internal fun shouldContinueAlerting(
    cyclesCompleted: Int,
    elapsedMs: Long,
    maxCycles: Int = 12,
    maxDurationMs: Long = 120_000L,
): Boolean = cyclesCompleted < maxCycles && elapsedMs < maxDurationMs

/**
 * Builds the alert notification the service posts via `startForeground`.
 * [alerting] true while the loop is running (shows a "Stop" action);
 * false after the cap is reached (shows only "Tap to dismiss").
 */
internal fun buildAlertNotification(
    context: Context,
    timerId: String,
    name: String?,
    alerting: Boolean,
): android.app.Notification {
    val title = name?.let { "${capitaliseFirst(it)} timer done" } ?: "Timer done"
    val contentIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val builder = NotificationCompat.Builder(context, NotificationChannels.TIMER_ALERT)
        .setSmallIcon(R.drawable.ic_ari_symbolic)
        .setContentTitle(title)
        .setContentText(if (alerting) "Alerting…" else "Tap to dismiss")
        .setAutoCancel(!alerting)
        .setOngoing(alerting)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentIntent)

    if (alerting) {
        val stopIntent = PendingIntent.getService(
            context,
            timerId.hashCode() xor 0x5701,
            TimerAlertService.stopIntent(context, timerId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        builder.addAction(0, "Stop", stopIntent)
    }
    return builder.build()
}

private fun capitaliseFirst(s: String): String =
    if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)
