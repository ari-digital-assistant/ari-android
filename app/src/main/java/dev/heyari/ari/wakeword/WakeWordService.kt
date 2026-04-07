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
import dev.heyari.ari.voice.VoiceOverlayActivity
import dev.heyari.ari.voice.VoiceSession
import dev.heyari.ari.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import javax.inject.Inject

@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject
    lateinit var voiceSession: VoiceSession

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

        startForeground(
            NOTIFICATION_ID,
            createListeningNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        startListening()
        return START_STICKY
    }

    private fun startListening() {
        if (isListening) return

        val modelBuffer = loadModelFromAssets()
        if (modelBuffer == null) {
            Log.e(TAG, "Failed to load wake word model")
            stopSelf()
            return
        }

        detector = MicroWakeWord(
            modelBuffer = modelBuffer,
            featureStepSizeMs = FEATURE_STEP_SIZE_MS,
            probabilityCutoff = PROBABILITY_CUTOFF,
            slidingWindowSize = SLIDING_WINDOW_SIZE
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
                    val samples = if (read == buffer.size) buffer else buffer.copyOf(read)
                    if (detector?.processAudio(samples) == true) {
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
        // Pause our mic so STT can take it. Always do this first regardless of
        // whether the activity launch succeeds — if we can't open the UI, we
        // still don't want to be hogging the mic.
        pauseListening()

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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setCategory(Notification.CATEGORY_ERROR)
            .setAutoCancel(true)
            .build()
        nm.notify(DETECTION_NOTIFICATION_ID, notification)
    }

    private fun loadModelFromAssets(): ByteBuffer? {
        return try {
            val inputStream = assets.open(MODEL_FILENAME)
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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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

        private const val MODEL_FILENAME = "hey_jarvis.tflite"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE = 160 // 10ms at 16kHz
        private const val FEATURE_STEP_SIZE_MS = 10
        private const val PROBABILITY_CUTOFF = 0.97f
        private const val SLIDING_WINDOW_SIZE = 5

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
