package dev.heyari.ari.voice

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.locale.AriFfiLocaleProvider
import dev.heyari.ari.stt.CloudTranscriber
import dev.heyari.ari.wakeword.WakeLog
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.FfiCloudSttSetup
import uniffi.ari_ffi.FfiLogLevel
import uniffi.ari_ffi.FfiVoiceHost
import uniffi.ari_ffi.FfiVoicePipeline

/**
 * The engine's voice pipeline, one for the app: the wake word, the listen
 * feed, speech-to-text and the voice session all live in it (ari-voice), and
 * this is what it asks of the app. The session's own needs are
 * [VoiceSession]'s.
 *
 * App-wide rather than the wake word service's, because speech-to-text needs
 * it whether or not the service is running: models load at start-up and on a
 * settings change. The wake word service registers for wakes.
 */
@Singleton
class VoiceEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    wakeLog: WakeLog,
    private val localeProvider: AriFfiLocaleProvider,
    private val cloudTranscriber: CloudTranscriber,
) {
    /** Called on the capture thread when Ari should wake. The wake word service's. */
    @Volatile
    var onWake: (() -> Unit)? = null

    val pipeline: FfiVoicePipeline = FfiVoicePipeline(Host(), wakeLog.engineLog)

    private inner class Host : FfiVoiceHost {
        override fun loadModel(name: String): ByteArray? = try {
            context.assets.open(name).use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}")
            null
        }

        override fun wakeDetected() {
            onWake?.invoke()
        }

        override fun log(level: FfiLogLevel, message: String) {
            when (level) {
                FfiLogLevel.TRACE, FfiLogLevel.DEBUG -> Log.d(TAG, message)
                FfiLogLevel.INFO -> Log.i(TAG, message)
                FfiLogLevel.WARN -> Log.w(TAG, message)
                FfiLogLevel.ERROR -> Log.e(TAG, message)
            }
        }

        override fun locale(): String = localeProvider.currentLocale()

        override fun cloudStt(): FfiCloudSttSetup? = cloudTranscriber.setup()
    }

    private companion object {
        const val TAG = "VoicePipeline"
    }
}
