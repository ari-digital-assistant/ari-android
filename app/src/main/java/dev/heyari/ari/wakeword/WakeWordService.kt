package dev.heyari.ari.wakeword

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.heyari.ari.MainActivity
import dev.heyari.ari.R
import dev.heyari.ari.audio.CaptureBus
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.voice.VoiceOverlayActivity
import dev.heyari.ari.voice.VoiceSession
import dev.heyari.ari.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import javax.inject.Inject

@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject
    lateinit var voiceSession: VoiceSession

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var captureBus: CaptureBus

    private var audioRecord: AudioRecord? = null
    private var detector: MicroWakeWord? = null
    private var isListening = false
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastDetectionAt = 0L
    private val detectionDebounceMs = 4_000L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Watch the voice session state. When it returns to Idle (i.e. the
        // overlay has dismissed), resume wake word listening.
        scope.launch {
            voiceSession.state.collect { state ->
                if (state is VoiceState.Idle && !isListening && isRunning) {
                    Log.i(TAG, "Voice session ended — resuming wake word listening")
                    startListening()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_LISTENING -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        try {
            startForeground(
                NOTIFICATION_ID,
                createListeningNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) extends
            // IllegalStateException — catching the superclass keeps this
            // working on older SDKs where the subclass isn't available.
            // Happens whenever the start context wasn't foreground enough for
            // a mic FGS: BOOT_COMPLETED, notification taps on A14+, etc.
            Log.w(TAG, "startForeground blocked — posting tap-to-start recovery", e)
            postTapToStartNotification(this)
            stopSelf()
            return START_NOT_STICKY
        }
        startListening()
        return START_STICKY
    }

    private fun startListening() {
        if (isListening) return

        // Datastore read on Main is a sub-millisecond cache hit after first
        // access — fine for service startup. We need it sync because the rest
        // of startListening() is sync and there's no audio loop yet to defer
        // into.
        // Datastore reads on Main are sub-ms cache hits after first access —
        // fine for service startup. Sync is required because the rest of
        // startListening() is sync and there's no audio loop yet to defer into.
        val activeId = runBlocking { settingsRepository.activeWakeWordId.first() }
        val sensitivityName = runBlocking { settingsRepository.wakeWordSensitivity.first() }
        val wakeWord = WakeWordRegistry.byId(activeId)
        val sensitivity = WakeWordSensitivity.fromName(sensitivityName)
        Log.i(TAG, "Loading wake word model: ${wakeWord.id} @ sensitivity=${sensitivity.name} (cutoff=${sensitivity.probabilityCutoff}, window=${sensitivity.slidingWindowSize})")

        val modelBuffer = loadModelFromAssets(wakeWord.assetFilename)
        if (modelBuffer == null) {
            Log.e(TAG, "Failed to load wake word model ${wakeWord.assetFilename}")
            stopSelf()
            return
        }

        detector = MicroWakeWord(
            modelBuffer = modelBuffer,
            featureStepSizeMs = wakeWord.featureStepSizeMs,
            probabilityCutoff = sensitivity.probabilityCutoff,
            slidingWindowSize = sensitivity.slidingWindowSize,
        )

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioRecord?.startRecording()
        isListening = true
        isRunning = true

        scope.launch {
            val buffer = ShortArray(CHUNK_SIZE)
            while (isListening) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    // Feed every chunk into the shared capture bus FIRST.
                    // Producer-side fan-out: ring buffer always; live channel
                    // iff a consumer (sherpa) is currently armed. Cheap and
                    // non-blocking by design — see CaptureBus.write().
                    captureBus.write(buffer, read)

                    val samples = if (read == buffer.size) buffer else buffer.copyOf(read)
                    if (detector?.processAudio(samples) == true) {
                        // Belt-and-braces: don't fire wake while STT is armed.
                        // The debounce below covers the common case but the
                        // mic is now permanently open, so an in-utterance fire
                        // is theoretically possible.
                        if (captureBus.armed) {
                            detector?.reset()
                            continue
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastDetectionAt < detectionDebounceMs) {
                            Log.d(TAG, "Wake word detected within debounce window — ignoring")
                            detector?.reset()
                            continue
                        }
                        lastDetectionAt = now
                        Log.i(TAG, "Wake word detected!")
                        onWakeWordDetected()
                        detector?.reset()
                    }
                }
            }
        }

        Log.i(TAG, "Wake word listening started")
    }

    /**
     * Stop our AudioRecord without tearing down the foreground service. Used
     * to release the mic to STT during a voice session, then resumed when the
     * session ends.
     */
    private fun pauseListening() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        detector?.close()
        detector = null
    }

    private fun onWakeWordDetected() {
        // NOTE: we no longer pauseListening() here. The mic stays open and is
        // shared with sherpa via CaptureBus. VoiceSession.start() will arm the
        // bus, snapshot the pre-roll, and start consuming live chunks — all
        // without ever closing AudioRecord. This is the whole point of the
        // unified pipeline refactor: zero-gap wake-to-STT.
        //
        // BAL gate: Android 14+ only allows a foreground service to start an
        // activity from the background during a brief grace window after the
        // FGS comes up (granted by FOREGROUND_SERVICE_TYPE_MICROPHONE). After
        // that grace expires, startActivity() throws BackgroundActivityStart-
        // Exception. The fix is to hold SYSTEM_ALERT_WINDOW, which permanently
        // grants UID-wide BAL privilege. We do NOT draw any overlay window —
        // SAW is purely held for its BAL side-effect. Same trick Signal,
        // Telegram, WhatsApp use for incoming-call screens over the keyguard.
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — cannot launch over lock screen. Posting recovery notification.")
            postSawMissingNotification()
            return
        }

        val intent = Intent(this, VoiceOverlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    or Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        @Suppress("DEPRECATION")
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= 34) {
                // Deprecated in API 36 in favour of an as-yet-unstable replacement;
                // the constant still works and SAW is the actual BAL grant anyway —
                // this just makes the intent explicit for OEM hardening.
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
        }.toBundle()

        try {
            startActivity(intent, options)
            Log.i(TAG, "Voice overlay activity launched")
        } catch (t: Throwable) {
            // Should not happen with SAW granted. If it does, surface the same
            // recovery notification — it's the only path the user can act on.
            Log.e(TAG, "Failed to launch voice overlay activity despite SAW being granted", t)
            postSawMissingNotification()
        }
    }

    /**
     * Posted when the wake word fired but we couldn't open the voice overlay
     * because SYSTEM_ALERT_WINDOW is not granted. Tapping deep-links into the
     * Android overlay-permission settings page for our package. This is the
     * ONLY notification path on the wake-word fire branch — the previous FSI
     * fallback was downgraded by NotificationManagerService anyway, so it gave
     * a false impression of success.
     */
    private fun postSawMissingNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            this, REQUEST_WAKE_DETECTED,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_DETECTION)
            .setContentTitle("Ari couldn't open")
            .setContentText("Tap to allow Ari to open from the lock screen")
            .setSmallIcon(R.drawable.ic_ari_symbolic)
            .setContentIntent(pi)
            .setCategory(Notification.CATEGORY_ERROR)
            .setAutoCancel(true)
            .build()
        nm.notify(DETECTION_NOTIFICATION_ID, notification)
    }

    private fun loadModelFromAssets(filename: String): ByteBuffer? {
        return try {
            val inputStream = assets.open(filename)
            val bytes = inputStream.readBytes()
            inputStream.close()
            val buffer = ByteBuffer.allocateDirect(bytes.size)
            buffer.put(bytes)
            buffer.rewind()
            buffer
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}")
            null
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val listeningChannel = NotificationChannel(
            CHANNEL_LISTENING,
            "Wake Word Listening",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Persistent notification while Ari listens for the wake word"
            setShowBadge(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(listeningChannel)

        val detectionChannel = NotificationChannel(
            CHANNEL_DETECTION,
            "Wake Word Detected",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alert when Ari hears the wake word"
            setShowBadge(true)
        }
        manager.createNotificationChannel(detectionChannel)
    }

    private fun createListeningNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WakeWordService::class.java).apply {
            action = ACTION_STOP_LISTENING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, REQUEST_STOP,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_LISTENING)
            .setContentTitle("Ari")
            .setContentText("Listening for wake word\u2026")
            .setSmallIcon(R.drawable.ic_ari_symbolic)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop Listening", stopPendingIntent
                ).build()
            )
            .build()
    }

    override fun onDestroy() {
        isListening = false
        isRunning = false
        scope.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        detector?.close()
        detector = null
        Log.i(TAG, "Wake word listening stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WakeWordService"

        private const val NOTIFICATION_ID = 1
        private const val DETECTION_NOTIFICATION_ID = 2

        private const val CHANNEL_LISTENING = "wake_word_listening"
        private const val CHANNEL_DETECTION = "wake_word_detection"

        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE = 160 // 10ms at 16kHz

        const val ACTION_STOP_LISTENING = "dev.heyari.ari.STOP_LISTENING"
        const val EXTRA_WAKE_WORD_DETECTED = "wake_word_detected"

        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_STOP = 1
        private const val REQUEST_WAKE_DETECTED = 2

        @Volatile
        var isRunning = false
            private set
    }
}
