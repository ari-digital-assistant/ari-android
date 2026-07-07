package dev.heyari.ari.actions

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.media.ariListenerComponent
import dev.heyari.ari.media.hasNotificationAccess
import dev.heyari.ari.media.percentToStreamVolume
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives system-wide media: transport (pause/resume/next/previous/stop) via the
 * most-recently-active [MediaController], and volume via [AudioManager]. Carries
 * no skill-specific knowledge — it acts on the generic `media` action.
 *
 * Decision logic is the pure [planMedia] (unit-tested); this class only applies
 * the resulting [TransportPlan] as side effects.
 */
@Singleton
class MediaTransportController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    sealed interface TransportPlan {
        data object RaiseVolume : TransportPlan
        data object LowerVolume : TransportPlan
        data class SetVolume(val index: Int) : TransportPlan
        data object Mute : TransportPlan
        data object Unmute : TransportPlan
        data class Transport(val command: String) : TransportPlan
        data object NeedsPermission : TransportPlan
        data object NothingPlaying : TransportPlan
        data class Failed(val reason: String) : TransportPlan
    }

    sealed interface TransportOutcome {
        data class Done(val action: String) : TransportOutcome
        data object NothingPlaying : TransportOutcome
        data object NeedsPermission : TransportOutcome
        data class Failed(val reason: String) : TransportOutcome
    }

    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun handle(m: MediaAction): TransportOutcome {
        val am = audio
        // Only query access/session for transport verbs — volume never needs them.
        val isTransport = m.action in TRANSPORT_ACTIONS
        val hasAccess = if (isTransport) hasNotificationAccess(context) else false
        val controller = if (isTransport && hasAccess) activeController() else null
        val plan = planMedia(
            m,
            hasAccess = hasAccess,
            hasActiveSession = controller != null,
            maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        )
        return when (plan) {
            TransportPlan.RaiseVolume -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                TransportOutcome.Done("volume")
            }
            TransportPlan.LowerVolume -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                TransportOutcome.Done("volume")
            }
            is TransportPlan.SetVolume -> {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, plan.index, AudioManager.FLAG_SHOW_UI)
                TransportOutcome.Done("volume")
            }
            TransportPlan.Mute -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                TransportOutcome.Done("volume")
            }
            TransportPlan.Unmute -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                TransportOutcome.Done("volume")
            }
            is TransportPlan.Transport -> {
                val tc = controller!!.transportControls
                when (plan.command) {
                    "pause" -> tc.pause()
                    "resume" -> tc.play()
                    "next" -> tc.skipToNext()
                    "previous" -> tc.skipToPrevious()
                    "stop" -> tc.stop()
                }
                TransportOutcome.Done(plan.command)
            }
            TransportPlan.NeedsPermission -> TransportOutcome.NeedsPermission
            TransportPlan.NothingPlaying -> TransportOutcome.NothingPlaying
            is TransportPlan.Failed -> TransportOutcome.Failed(plan.reason)
        }
    }

    /** Most-recently-active session, or null when nothing is playing. */
    private fun activeController(): MediaController? = try {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm.getActiveSessions(ariListenerComponent(context)).firstOrNull()
    } catch (t: Throwable) {
        Log.w(TAG, "getActiveSessions failed", t)
        null
    }

    companion object {
        private const val TAG = "MediaTransport"
        val TRANSPORT_ACTIONS = setOf("pause", "resume", "next", "previous", "stop")
    }
}

/**
 * Pure decision: given the media action and the runtime facts (access granted,
 * a session is active, and the max stream volume), what should happen? No
 * Android calls — fully unit-testable.
 */
fun planMedia(
    m: MediaAction,
    hasAccess: Boolean,
    hasActiveSession: Boolean,
    maxVolume: Int,
): MediaTransportController.TransportPlan {
    if (m.action == "volume") {
        return when {
            m.mute == true -> MediaTransportController.TransportPlan.Mute
            m.mute == false -> MediaTransportController.TransportPlan.Unmute
            m.level != null ->
                MediaTransportController.TransportPlan.SetVolume(percentToStreamVolume(m.level, maxVolume))
            m.direction == "up" -> MediaTransportController.TransportPlan.RaiseVolume
            m.direction == "down" -> MediaTransportController.TransportPlan.LowerVolume
            else -> MediaTransportController.TransportPlan.Failed("volume with no direction/level/mute")
        }
    }
    if (m.action !in MediaTransportController.TRANSPORT_ACTIONS) {
        return MediaTransportController.TransportPlan.Failed("unknown media action ${m.action}")
    }
    if (!hasAccess) return MediaTransportController.TransportPlan.NeedsPermission
    if (!hasActiveSession) return MediaTransportController.TransportPlan.NothingPlaying
    return MediaTransportController.TransportPlan.Transport(m.action)
}
