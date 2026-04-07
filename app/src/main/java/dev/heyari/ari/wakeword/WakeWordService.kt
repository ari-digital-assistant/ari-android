package dev.heyari.ari.wakeword

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
import android.os.IBinder
import android.util.Log
import dev.heyari.ari.MainActivity
import dev.heyari.ari.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class WakeWordService : Service() {

    private var audioRecord: AudioRecord? = null
    private var detector: MicroWakeWord? = null
    private var isListening = false
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
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
                        Log.i(TAG, "Wake word detected!")
                        onWakeWordDetected()
                        detector?.reset()
                    }
                }
            }
        }

        Log.i(TAG, "Wake word listening started")
    }

    private fun onWakeWordDetected() {
        val nm = getSystemService(NotificationManager::class.java)
        val canFsn = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nm.canUseFullScreenIntent()
        } else {
            true
        }
        Log.i(TAG, "Wake word detected! canUseFullScreenIntent=$canFsn")

        // Target the trampoline activity rather than MainActivity directly.
        // The trampoline lives in its own task affinity and is never in recents,
        // so Android always considers it "sleeping" — meaning the FSN policy
        // actually fires the full-screen intent instead of degrading to a
        // heads-up banner when MainActivity was the most recently focused app.
        val openIntent = Intent(this, WakeTrampolineActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, REQUEST_WAKE_DETECTED,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_DETECTION)
            .setContentTitle("Ari")
            .setContentText("Wake word detected — tap to open")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setCategory(Notification.CATEGORY_CALL)
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
