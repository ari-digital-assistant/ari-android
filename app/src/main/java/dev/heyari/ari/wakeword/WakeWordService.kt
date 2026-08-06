package dev.heyari.ari.wakeword

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.heyari.ari.MainActivity
import dev.heyari.ari.R
import dev.heyari.ari.audio.CaptureBus
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.listening.ConditionSignals
import dev.heyari.ari.listening.ListeningController
import dev.heyari.ari.listening.ListeningDecision
import dev.heyari.ari.listening.ListeningMode
import dev.heyari.ari.listening.decideListening
import dev.heyari.ari.voice.CaptureMode
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

    @Inject
    lateinit var listeningController: ListeningController

    /**
     * The policy's latest word on whether the mic should be open. Seeded
     * synchronously in [onStartCommand] from the stored preferences alone —
     * the live condition signals haven't arrived that early, so a Custom user
     * deliberately starts COLD and warms up a few milliseconds later if one of
     * their conditions turns out to hold. Erring the other way would open the
     * microphone on someone who had asked it not to be.
     */
    @Volatile
    private var decision: ListeningDecision = ListeningDecision.Listen

    private var audioRecord: AudioRecord? = null
    private var detector: MicroWakeWord? = null
    private var captureThread: Thread? = null

    // Read/mutated from both StateFlow collectors (state + captureMode) which run
    // on DIFFERENT threads of the multi-threaded Default dispatcher. @Volatile
    // gives safe publication; the @Synchronized mic-lifecycle methods below make
    // the check-then-act (`if (isListening) return`) atomic so two threads can
    // never both open the mic on a barge-in conversation exit.
    @Volatile
    private var isListening = false

    // Which AudioSource the always-on mic is currently opened on. Flips to
    // VOICE_COMMUNICATION during a "let's talk" conversation so the phone's
    // hardware AEC strips Ari's own TTS out of the capture.
    @Volatile
    private var currentSource: Int = audioSourceFor(CaptureMode.NORMAL)

    // While true the wake detector is skipped (we're mid-conversation), but
    // audio still flows into the CaptureBus every chunk.
    @Volatile
    private var wakePaused: Boolean = false
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastDetectionAt = 0L
    private val detectionDebounceMs = 4_000L

    // Set true the moment a one-shot tap-to-talk turn leaves Idle, so the state
    // collector can tell "turn actually started" from the initial Idle emission
    // and only stand the capture host down after a real turn completes.
    @Volatile
    private var oneShotTurnBegan = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Watch the voice session state. When it returns to Idle (i.e. the
        // overlay has dismissed), resume wake word listening.
        scope.launch {
            voiceSession.state.collect { state ->
                if (oneShotActive) {
                    // Tap-to-talk one-shot: this service was started purely as a
                    // transient capture host (always-listening is OFF). Don't
                    // resume wake listening — instead stand the whole service
                    // down once the turn we triggered returns to Idle, so the
                    // mic doesn't stay hot. The oneShotTurnBegan guard stops the
                    // collector's INITIAL Idle emission (which arrives before the
                    // overlay has started the session) from killing us early.
                    if (state !is VoiceState.Idle) {
                        oneShotTurnBegan = true
                    } else if (oneShotTurnBegan) {
                        Log.i(TAG, "One-shot voice turn ended — standing down capture host")
                        stopSelf()
                    }
                    return@collect
                }
                if (state is VoiceState.Idle && isRunning) {
                    // The turn is over — hand the microphone back to the policy,
                    // which may well want it closed again.
                    Log.i(TAG, "Voice session ended — re-applying listening policy")
                    applyDecision()
                }
            }
        }
        // React to conversation ("let's talk") entering/exiting: swap the mic
        // source, flip AudioManager mode, and pause/resume wake detection.
        scope.launch {
            voiceSession.captureMode.collect { mode ->
                if (isRunning) applyCaptureMode(mode)
            }
        }
        // The listening policy. Every condition source hangs off this
        // subscription, so nothing is registered until the service is up and
        // everything is torn down when the scope dies.
        scope.launch {
            listeningController.decisions.collect { next ->
                Log.i(TAG, "Listening decision: $next")
                decision = next
                applyDecision()
            }
        }
    }

    /**
     * Bring the microphone into line with [decision].
     *
     * [ListeningDecision.StandBy] releases the mic but keeps the foreground
     * service alive, and that is load-bearing rather than lazy: Android 14+
     * refuses to START a microphone FGS from the background, but one that is
     * already running keeps its microphone capability for its whole life and
     * may reopen `AudioRecord` freely. Calling `stopForeground` here would
     * strand us — nothing could get the mic back without a visible activity.
     */
    @Synchronized
    private fun applyDecision() {
        // A one-shot capture host answers to the tap that created it, not to
        // the policy. The user asked for this turn out loud.
        if (oneShotActive) return
        // Never yank the mic out from under a live turn. The state collector
        // above re-applies the decision the moment it returns to Idle.
        if (voiceSession.isActive) return

        when (decision) {
            is ListeningDecision.Listen -> startListening()
            is ListeningDecision.StandBy -> releaseMic()
            ListeningDecision.Off -> {
                stopSelf()
                return
            }
        }
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_LISTENING -> {
                // The notification's stop action sets the mode itself, so it
                // has to persist like the top-bar control does. Stopping the
                // service alone would leave the top-bar control claiming Ari
                // was still armed, and nothing would bring it back at the next
                // schedule boundary. This doesn't touch the stored conditions/
                // schedules/places — switching back to Custom later restores
                // exactly what was configured.
                runBlocking { settingsRepository.setListeningMode(ListeningMode.NEVER) }
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // One gate for every start path — boot, notification tap, the settings
        // switch, tap-to-talk, dictation — instead of six callers each hoping.
        // A14+ throws SecurityException out of startForeground when a
        // MICROPHONE-typed FGS doesn't hold RECORD_AUDIO, and the catch below
        // only covers IllegalStateException, so an intervening revoke would
        // take the service down hard rather than log and stand down.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted — refusing to start")
            stopSelf()
            return START_NOT_STICKY
        }

        // Seeded from the stored preferences before the first notification is
        // built, so a standing-by start never flashes "Listening for Hey Ari"
        // at someone whose screen is off. Two DataStore reads on Main, both
        // sub-millisecond cache hits after first access — the same trade
        // startListening() already makes below.
        decision = runBlocking {
            decideListening(
                mode = settingsRepository.listeningMode.first(),
                conditions = settingsRepository.listeningConditions.first(),
                signals = ConditionSignals(),
            )
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
        // The capture host now exists, which is what isRunning has always meant
        // to its callers. Whether the mic is actually open is micHot's job — the
        // two came apart the moment standing by became a state.
        isRunning = true

        val action = intent?.action
        if (action == ACTION_START_VOICE_TURN || action == ACTION_START_DICTATION) {
            // An explicit summon outranks the policy: the user tapped the mic.
            startListening()
        } else {
            applyDecision()
        }

        if (intent?.action == ACTION_START_VOICE_TURN) {
            // Tap-to-talk: the composer's mic button wants a turn NOW, with no
            // spoken wake word. The mic + CaptureBus are open (startListening
            // above opened them on a cold start, or they were already running),
            // so launch the overlay exactly as a wake detection would. This is a
            // FOREGROUND user tap, so BAL is already granted — no SAW gate here.
            //
            // EXTRA_ONE_SHOT is set when always-listening was OFF: it marks this
            // service run as a transient capture host that stands itself down
            // after the turn (see the state collector in onCreate). When
            // always-listening was already ON we pass false and just keep going.

            // startListening() bails via stopSelf() WITHOUT setting micHot if
            // the wake model fails to load. Don't launch a doomed overlay onto a
            // dead capture host — and don't come back sticky.
            if (!micHot) {
                Log.w(TAG, "Capture host failed to start — not launching voice turn")
                return START_NOT_STICKY
            }

            oneShotActive = intent.getBooleanExtra(EXTRA_ONE_SHOT, false)
            oneShotTurnBegan = false
            val launched = launchVoiceOverlay(verifyWake = false)
            if (!launched && oneShotActive) {
                // One-shot host with no turn to end it — don't leave the mic
                // hot. (Always-on keeps running: the mic belongs to it anyway.)
                Log.w(TAG, "One-shot overlay launch failed — standing down capture host")
                stopSelf()
            }
            if (oneShotActive) {
                // One-shot capture host: if the OS kills us mid-turn, a sticky
                // restart would redeliver a NULL intent — oneShotActive would
                // default false and we'd resurrect as a FULL always-listening
                // host (hot mic + lit switch the user never enabled). Refuse the
                // sticky restart; always-on below keeps START_STICKY as before.
                return START_NOT_STICKY
            }
        }

        if (intent?.action == ACTION_START_DICTATION) {
            // Foreground in-place dictation: same transient capture host as
            // tap-to-talk, but STT-only — no overlay. VoiceSession.startDictation()
            // streams partials to the composer and emits the final transcript;
            // reaching Idle stands this host down via the same one-shot collector
            // in onCreate.
            if (!micHot) {
                Log.w(TAG, "Capture host failed to start — not starting dictation")
                return START_NOT_STICKY
            }
            if (voiceSession.isActive) {
                // Redundant start (double-tap race): a dictation session is
                // already running — don't reset the one-shot flags out from
                // under it.
                Log.w(TAG, "Dictation already active — ignoring redundant start")
                return if (oneShotActive) START_NOT_STICKY else START_STICKY
            }
            oneShotActive = intent.getBooleanExtra(EXTRA_ONE_SHOT, false)
            oneShotTurnBegan = false
            voiceSession.startDictation()
            if (oneShotActive) {
                // Same START_NOT_STICKY reasoning as the voice-turn branch: a
                // sticky NULL-intent restart must not resurrect a full host.
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    // @Synchronized (monitor = `this`) serialises every mic-lifecycle transition
    // so the two collector threads can't both pass the `if (isListening) return`
    // guard and double-open the AudioRecord. Reentrant, so switchSource() calling
    // this is fine. The monitor is held only for setup: the read loop is launched
    // and the method returns, so the loop never holds the lock for its lifetime.
    @Synchronized
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

        val record = openAudioRecord(currentSource)
        audioRecord = record

        record.startRecording()
        isListening = true
        micHot = true

        // Its own thread, not a slot on the shared Default dispatcher: read()
        // blocks for a whole buffer period, so parking it on a pool thread the
        // detector and the rest of the app also use is a stall waiting to happen.
        captureThread = Thread({ captureLoop(record) }, "AriMicCapture").also { it.start() }

        Log.i(TAG, "Wake word listening started")
    }

    private fun captureLoop(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ShortArray(CHUNK_SIZE)
        while (isListening) {
            val read = record.read(buffer, 0, buffer.size)
            if (read < 0) {
                // Released out from under us, or the HAL died. Either way there
                // is no audio coming — spinning on the error would burn exactly
                // the battery this loop exists to save.
                Log.w(TAG, "AudioRecord.read failed ($read) — ending capture loop")
                return
            }
            if (read == 0) continue

            // Feed every chunk into the shared capture bus FIRST.
            // Producer-side fan-out: ring buffer always; live channel
            // iff a consumer (sherpa) is currently armed. Cheap and
            // non-blocking by design — see CaptureBus.write().
            captureBus.write(buffer, read)

            // Mid-conversation: audio still feeds the bus (STT/AEC path)
            // but we do NOT run wake detection.
            if (wakePaused) continue
            val samples = if (read == buffer.size) buffer else buffer.copyOf(read)
            if (detector?.processAudio(samples) != true) continue

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

    @SuppressLint("MissingPermission") // gated on RECORD_AUDIO in onStartCommand()
    private fun openAudioRecord(source: Int): AudioRecord {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ) * BUFFER_SIZE_FACTOR
        return AudioRecord(
            source,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
    }

    /**
     * End the read loop, wait for the capture thread to leave [AudioRecord.read],
     * then release the mic and the detector. The join matters: tearing either one
     * down while the loop is still inside it means native code operating on freed
     * state.
     */
    private fun releaseMic() {
        isListening = false
        micHot = false
        captureThread?.let { thread ->
            thread.join(CAPTURE_JOIN_TIMEOUT_MS)
            if (thread.isAlive) Log.w(TAG, "Capture thread still alive after ${CAPTURE_JOIN_TIMEOUT_MS}ms")
        }
        captureThread = null
        audioRecord?.let { runCatching { it.stop(); it.release() } }
        audioRecord = null
        detector?.close()
        detector = null
    }

    /**
     * React to a capture-mode transition. CONVERSATION opens the mic on
     * VOICE_COMMUNICATION and puts AudioManager into comms mode so the hardware
     * AEC removes Ari's own TTS from the capture; NORMAL restores plain MIC +
     * MODE_NORMAL. The wake detector is paused for the duration of the chat.
     */
    private fun applyCaptureMode(mode: CaptureMode) {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        when (mode) {
            CaptureMode.CONVERSATION -> {
                switchSource(audioSourceFor(CaptureMode.CONVERSATION))
                am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = true }
                wakePaused = true
            }
            CaptureMode.NORMAL -> {
                wakePaused = false
                am.mode = android.media.AudioManager.MODE_NORMAL
                @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false }
                switchSource(audioSourceFor(CaptureMode.NORMAL))
            }
        }
    }

    /** Stop the loop, release, reopen on [source], restart. No-op if unchanged. */
    @Synchronized
    private fun switchSource(source: Int) {
        if (source == currentSource && audioRecord != null) return
        val wasListening = isListening
        // Also closes the current detector — startListening() builds a fresh one,
        // so skipping this leaks the native handle on every source flip.
        releaseMic()
        currentSource = source
        // Reopens with currentSource and relaunches the loop. Safe because
        // isListening is now false, so its early-return guard won't trip.
        if (wasListening) startListening()
    }

    private fun onWakeWordDetected() {
        // NOTE: we no longer release the mic here. It stays open and is
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

        launchVoiceOverlay(verifyWake = true)
    }

    /**
     * Launch the transparent [VoiceOverlayActivity], which starts the shared
     * [VoiceSession] and renders the turn UI. Shared by two callers:
     *  - the wake-detection path ([onWakeWordDetected]), which gates on SAW
     *    FIRST because it launches from the background / over the lock screen;
     *  - the tap-to-talk path ([onStartCommand] handling ACTION_START_VOICE_TURN),
     *    a foreground user tap that already has BAL, so it needs no SAW gate.
     *
     * Returns true if the overlay launch was dispatched. The one-shot tap path
     * uses this to stand the capture host down if the launch failed — otherwise
     * the service would sit with the mic hot waiting for a turn that never began.
     */
    private fun launchVoiceOverlay(verifyWake: Boolean): Boolean {
        val intent = Intent(this, VoiceOverlayActivity::class.java).apply {
            putExtra(EXTRA_VERIFY_WAKE, verifyWake)
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

        return try {
            startActivity(intent, options)
            Log.i(TAG, "Voice overlay activity launched")
            true
        } catch (t: Throwable) {
            // Should not happen: the wake path holds SAW and the tap path is
            // foreground. If it does, surface the recovery notification — it's
            // the only path the user can act on.
            Log.e(TAG, "Failed to launch voice overlay activity", t)
            postSawMissingNotification()
            false
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
            .setContentTitle(getString(R.string.notif_wake_couldnt_open_title))
            .setContentText(getString(R.string.notif_wake_couldnt_open_text))
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
            getString(R.string.notif_channel_wake_listening_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notif_channel_wake_listening_description)
            setShowBadge(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(listeningChannel)

        val detectionChannel = NotificationChannel(
            CHANNEL_DETECTION,
            getString(R.string.notif_channel_wake_detected_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_channel_wake_detected_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(detectionChannel)
    }

    /**
     * Push the current [decision] into the already-posted foreground
     * notification. `notify` with the same id updates in place — it does not
     * re-post, so the user gets no sound and no re-appearance.
     */
    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createListeningNotification())
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

        // Standing by is a real, honest state and the notification says so —
        // the microphone genuinely is closed and the privacy indicator is dark,
        // so claiming to be listening would be a lie the user could catch.
        val standbyReason = (decision as? ListeningDecision.StandBy)?.reason

        return Notification.Builder(this, CHANNEL_LISTENING)
            .setContentTitle(
                getString(
                    if (standbyReason == null) R.string.notif_wake_listening_title
                    else R.string.notif_wake_standby_title
                )
            )
            .setContentText(
                if (standbyReason == null) getString(R.string.notif_wake_listening_text)
                else getString(standbyReason.messageRes)
            )
            .setSmallIcon(R.drawable.ic_ari_symbolic)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.notif_wake_stop_action), stopPendingIntent
                ).build()
            )
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        micHot = false
        scope.cancel()
        releaseMic()
        // Never leave the always-on path stranded in comms mode: restore normal
        // audio routing + MIC source so the next start listens cleanly.
        runCatching {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            am.mode = android.media.AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false }
        }
        wakePaused = false
        currentSource = audioSourceFor(CaptureMode.NORMAL)
        oneShotActive = false
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

        // 30ms at 16kHz. The models step features every 10ms, so one read now
        // yields three feature frames instead of one — same detection, a third
        // of the thread wake-ups.
        private const val CHUNK_SIZE = 480

        // AudioRecord hands out the low-latency FAST capture path when you ask
        // for a buffer near the minimum. That path runs a short HAL burst period
        // to shave milliseconds a wake word does not care about, and we pay for
        // it in wake-ups all day. Asking for well above the minimum opts out.
        private const val BUFFER_SIZE_FACTOR = 8

        // read() returns every buffer period, so a join this long only expires if
        // the HAL has wedged — and onDestroy runs on the main thread, so we
        // cannot wait on it forever.
        private const val CAPTURE_JOIN_TIMEOUT_MS = 500L

        const val ACTION_STOP_LISTENING = "dev.heyari.ari.STOP_LISTENING"
        const val EXTRA_WAKE_WORD_DETECTED = "wake_word_detected"

        // Tap-to-talk: start a voice turn immediately, no spoken wake word.
        // EXTRA_ONE_SHOT true means always-listening was OFF, so the service is
        // a transient capture host that stands itself down after the turn.
        const val ACTION_START_VOICE_TURN = "dev.heyari.ari.START_VOICE_TURN"
        const val ACTION_START_DICTATION = "dev.heyari.ari.START_DICTATION"
        const val EXTRA_ONE_SHOT = "one_shot"

        // True only for turns started by an actual wake-word detection. The
        // tap-to-talk path shares launchVoiceOverlay() but has no wake phrase
        // in its pre-roll, so verifying it would bin every tap-to-talk turn.
        const val EXTRA_VERIFY_WAKE = "verify_wake"

        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_STOP = 1
        private const val REQUEST_WAKE_DETECTED = 2

        /**
         * The capture host exists. Says nothing about whether the microphone is
         * open — see [micHot]. The two were the same thing until standing by
         * became a state.
         */
        @Volatile
        var isRunning = false
            private set

        /** The microphone is actually open and the wake detector is running. */
        @Volatile
        var micHot = false
            private set

        // True while THIS service run is a transient tap-to-talk capture host
        // (started with EXTRA_ONE_SHOT because always-listening was OFF). The
        // UI reads it alongside isRunning so the always-listening switch does
        // NOT light up during a one-shot turn, and it isn't a wake-word run.
        @Volatile
        var oneShotActive = false
            private set
    }
}

/**
 * Which AudioSource each capture mode records on. NORMAL uses
 * VOICE_RECOGNITION: the CDD requires it to disable AGC and noise
 * suppression with a flat response — plain MIC on Pixel pumps room
 * tone to a -25 dB floor with peaks pinned at 0 dBFS, which is what
 * was drowning STT (see docs/superpowers/plans/2026-07-30-audio-
 * pipeline-and-reminder-fixes.md). CONVERSATION stays on
 * VOICE_COMMUNICATION for the hardware AEC that cancels Ari's TTS.
 */
internal fun audioSourceFor(mode: CaptureMode): Int = when (mode) {
    CaptureMode.NORMAL -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    CaptureMode.CONVERSATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
}
