package dev.heyari.ari.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.R
import dev.heyari.ari.media.openNotificationListenerSettings
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses skill envelopes (one schema for every skill — see
 * `ari-skills/docs/action-responses.md`) and dispatches each primitive to
 * the right Android-side handler. Returns an [ActionResult.Spoken] with the
 * envelope's `speak` text plus any attachments the bubble should render
 * underneath.
 *
 * Single-shot slots (`launch_app`, `search`, `open_url`, `clipboard`) take
 * effect immediately; rich primitives (`cards`, `alerts`, `notifications`,
 * `dismiss.*`) flow through [PresentationCoordinator]. Both can coexist
 * in one envelope (e.g. a clipboard copy + a confirmation card).
 */
@Singleton
class ActionHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appLauncher: AppLauncher,
    private val webSearchLauncher: WebSearchLauncher,
    private val musicLauncher: MusicLauncher,
    private val alarmLauncher: AlarmLauncher,
    private val mediaTransportController: MediaTransportController,
    private val presentationCoordinator: PresentationCoordinator,
) {

    fun handle(json: String, skillId: String): ActionResult.Spoken {
        val obj = try {
            JSONObject(json)
        } catch (t: Throwable) {
            Log.e(TAG, "invalid envelope JSON: $json", t)
            return ActionResult.Spoken("I couldn't understand that action.")
        }
        val env = PresentationEnvelope.parse(obj, skillId)
            ?: return ActionResult.Spoken("I couldn't understand that action.")

        // Single-shot slots first. The skill may have omitted `speak` for
        // these (so the frontend can produce platform-appropriate phrasing
        // like "Opening Spotify"). If the skill DID set `speak`, it wins.
        env.launchApp?.let { return ActionResult.Spoken(env.speak ?: handleOpen(it)) }
        env.search?.let { return ActionResult.Spoken(env.speak ?: handleSearch(it)) }
        env.openUrl?.let { return ActionResult.Spoken(env.speak ?: handleOpenUrl(it)) }
        env.media?.let { return ActionResult.Spoken(env.speak ?: handleMedia(it)) }
        env.clipboardText?.let { copyToClipboard(it) }

        // Alarm hand-off. On success, fall through to the shared tail below so
        // the confirm card renders and env.speak is spoken; only override the
        // spoken line (returning early) when there's no Clock app to handle it.
        env.alarm?.let { alarm ->
            if (alarmLauncher.launch(alarm) is AlarmLauncher.LaunchResult.NoClockApp) {
                return ActionResult.Spoken("I couldn't find a clock app to set that.")
            }
        }

        val attachments = if (env.hasPresentationPrimitives()) {
            presentationCoordinator.apply(env)
        } else {
            emptyList()
        }
        return ActionResult.Spoken(env.speak ?: "", attachments, env.runUtterance)
    }

    private fun handleOpen(target: String): String {
        if (target.isBlank()) return "What would you like me to open?"
        return when (val result = appLauncher.launch(target)) {
            is AppLauncher.LaunchResult.Launched ->
                "Opening ${result.app.label}."
            is AppLauncher.LaunchResult.NotFound ->
                "I couldn't find an app called ${result.target}."
            is AppLauncher.LaunchResult.Failed ->
                "I couldn't open ${result.app.label}: ${result.reason}."
        }
    }

    private fun handleSearch(query: String): String {
        if (query.isBlank()) return "What would you like me to search for?"
        return when (val result = webSearchLauncher.search(query)) {
            is WebSearchLauncher.SearchResult.Launched ->
                "Searching for ${result.query}."
            is WebSearchLauncher.SearchResult.Failed ->
                "I couldn't search: ${result.reason}."
        }
    }

    private fun handleMedia(m: MediaAction): String {
        if (m.action == "play") {
            val query = m.query ?: return "What would you like me to play?"
            return when (val r = musicLauncher.play(query, m.service)) {
                is MusicLauncher.PlayResult.Playing ->
                    if (r.serviceName != null) "Playing ${r.query} on ${r.serviceName}."
                    else "Playing ${r.query}."
                is MusicLauncher.PlayResult.OpenedResults ->
                    "Here are results for ${r.query} on ${r.serviceName}."
                is MusicLauncher.PlayResult.ServiceNotInstalled ->
                    "You don't have ${r.serviceName} installed."
                is MusicLauncher.PlayResult.NoMusicApp ->
                    "I couldn't find a music app to play that."
                is MusicLauncher.PlayResult.Failed ->
                    "I couldn't play that: ${r.reason}."
            }
        }
        return when (val o = mediaTransportController.handle(m)) {
            is MediaTransportController.TransportOutcome.Done -> {
                val f = doneFeedback(o.action, m.level, m.mute)
                when {
                    f.resId == null -> ""
                    f.arg != null -> context.getString(f.resId, f.arg)
                    else -> context.getString(f.resId)
                }
            }
            MediaTransportController.TransportOutcome.NothingPlaying ->
                context.getString(R.string.media_nothing_playing)
            MediaTransportController.TransportOutcome.NeedsPermission -> {
                openNotificationListenerSettings(context)
                context.getString(R.string.media_needs_permission)
            }
            is MediaTransportController.TransportOutcome.Failed -> {
                Log.w(TAG, "media transport failed: ${o.reason}")
                ""
            }
        }
    }

    private fun handleOpenUrl(url: String): String {
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Opening that link."
        }.getOrElse { t ->
            Log.w(TAG, "open_url failed for $url", t)
            "I couldn't open that link."
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService<ClipboardManager>() ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Ari", text))
    }

    companion object {
        private const val TAG = "ActionHandler"
    }
}
