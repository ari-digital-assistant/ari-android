package dev.heyari.ari.stt

import android.content.Context
import android.content.Intent
import java.io.File
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.audio.AudioClipStore
import dev.heyari.ari.audio.ClipStats
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the user said to Ari, with every transcript the recogniser produced
 * for it, so mis-hearings can be diagnosed against the actual audio. The voice
 * session (ari-voice `captures`) writes it, behind a debug setting that is off
 * by default; this is the settings page's and the bug report's view of it.
 */
@Singleton
class UtteranceCaptureStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val clips = AudioClipStore(context, DIR_NAME)

    fun stats(): ClipStats = clips.stats()

    fun clear() = clips.clear()

    fun shareIntent(): Intent? = clips.shareIntent()

    /** Every captured clip and its sidecar, for the share sheet or a bug report. */
    fun files(): List<File> = clips.files()

    // Internal rather than private: see WakeCaptureStore's companion.
    internal companion object {
        const val DIR_NAME = "utterance-captures"
    }
}
