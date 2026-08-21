package dev.heyari.ari.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.R
import dev.heyari.ari.locale.AriFfiLocaleProvider
import dev.heyari.ari.media.openNotificationListenerSettings
import java.util.Locale
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses skill envelopes (one schema for every skill — see
 * `ari-skills/docs/reference-actions.md`) and dispatches each primitive to
 * the right Android-side handler. Returns an [ActionResult.Spoken] with the
 * envelope's `speak` text plus any attachments the bubble should render
 * underneath.
 *
 * Single-shot slots (`launch_app`, `search`, `open_url`, `media`, `navigate`,
 * `message`, `reply`, `clipboard`, `alarm`) take effect immediately; rich primitives (`cards`,
 * `alerts`, `notifications`, `dismiss.*`) flow through [PresentationCoordinator].
 * Both coexist in one envelope (e.g. a media action plus a now-playing card):
 * every slot falls through to the shared presentation tail. The only early
 * returns are hard failures where the action never happened (no Maps app, no
 * Clock app), which skip presentation and just speak the failure.
 *
 * Frontend-authored conversation replies (the strings the frontend produces
 * when a skill omitted `speak`) follow Ari's *conversation* locale — the
 * in-app language exposed by [AriFfiLocaleProvider.currentLocale] — not the
 * app/system UI locale. See [say].
 */
@Singleton
class ActionHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appLauncher: AppLauncher,
    private val webSearchLauncher: WebSearchLauncher,
    private val musicLauncher: MusicLauncher,
    private val alarmLauncher: AlarmLauncher,
    private val navigationLauncher: NavigationLauncher,
    private val messageLauncher: MessageLauncher,
    private val replyLauncher: ReplyLauncher,
    private val mediaTransportController: MediaTransportController,
    private val presentationCoordinator: PresentationCoordinator,
    private val localeProvider: AriFfiLocaleProvider,
) {

    fun handle(json: String, skillId: String): ActionResult.Spoken {
        val obj = try {
            JSONObject(json)
        } catch (t: Throwable) {
            Log.e(TAG, "invalid envelope JSON: $json", t)
            return ActionResult.Spoken(say(R.string.action_reply_not_understood))
        }
        val env = PresentationEnvelope.parse(obj, skillId)
            ?: return ActionResult.Spoken(say(R.string.action_reply_not_understood))

        // Single-shot slots. Each performs its side effect and contributes the
        // spoken line, then FALLS THROUGH to the shared presentation tail so any
        // cards / alerts / notifications in the same envelope still render
        // (previously these returned early and silently dropped them). The skill
        // may omit `speak` so the frontend can supply platform-appropriate
        // phrasing like "Opening Spotify"; if the skill DID set `speak`, it wins.
        var spoken: String? = null
        env.launchApp?.let { spoken = env.speak ?: handleOpen(it) }
        env.search?.let { spoken = env.speak ?: handleSearch(it) }
        env.openUrl?.let { spoken = env.speak ?: handleOpenUrl(it) }
        env.media?.let { spoken = env.speak ?: handleMedia(it) }
        env.navigate?.let { nav ->
            when (navigationLauncher.launch(nav)) {
                NavigationLauncher.LaunchResult.Launched -> spoken = env.speak ?: ""
                // No Maps app: navigation never happened, so speak the failure
                // and skip presentation (mirrors the alarm no-Clock-app path).
                NavigationLauncher.LaunchResult.NoMapsApp ->
                    return ActionResult.Spoken(say(R.string.action_reply_navigate_no_maps))
            }
        }
        env.message?.let { spoken = env.speak ?: handleMessage(it) }
        env.reply?.let { spoken = env.speak ?: handleReply(it) }
        env.clipboardText?.let { copyToClipboard(it) }

        // Alarm hand-off. On success, fall through to the shared tail below so
        // the confirm card renders and env.speak is spoken; only override the
        // spoken line (returning early, skipping presentation) when there's no
        // Clock app to handle it.
        env.alarm?.let { alarm ->
            if (alarmLauncher.launch(alarm) is AlarmLauncher.LaunchResult.NoClockApp) {
                return ActionResult.Spoken(say(R.string.action_reply_alarm_no_clock))
            }
        }

        val attachments = if (env.hasPresentationPrimitives()) {
            presentationCoordinator.apply(env)
        } else {
            emptyList()
        }
        return ActionResult.Spoken(spoken ?: env.speak ?: "", attachments, env.runUtterance)
    }

    private fun handleOpen(target: String): String {
        if (target.isBlank()) return say(R.string.action_reply_open_what)
        return when (val result = appLauncher.launch(target)) {
            is AppLauncher.LaunchResult.Launched ->
                say(R.string.action_reply_open_opening, result.app.label)
            is AppLauncher.LaunchResult.NotFound ->
                say(R.string.action_reply_open_not_found, result.target)
            is AppLauncher.LaunchResult.Failed ->
                say(R.string.action_reply_open_failed, result.app.label, result.reason)
        }
    }

    private fun handleSearch(query: String): String {
        if (query.isBlank()) return say(R.string.action_reply_search_what)
        return when (val result = webSearchLauncher.search(query)) {
            is WebSearchLauncher.SearchResult.Launched ->
                say(R.string.action_reply_search_searching, result.query)
            is WebSearchLauncher.SearchResult.Failed ->
                say(R.string.action_reply_search_failed, result.reason)
        }
    }

    private fun handleMedia(m: MediaAction): String {
        if (m.action == "play") {
            val query = m.query ?: return say(R.string.action_reply_play_what)
            return when (val r = musicLauncher.play(query, m.service)) {
                is MusicLauncher.PlayResult.Playing ->
                    if (r.serviceName != null)
                        say(R.string.action_reply_play_playing_on, r.query, r.serviceName)
                    else
                        say(R.string.action_reply_play_playing, r.query)
                is MusicLauncher.PlayResult.OpenedResults ->
                    say(R.string.action_reply_play_results, r.query, r.serviceName)
                is MusicLauncher.PlayResult.ServiceNotInstalled ->
                    say(R.string.action_reply_play_not_installed, r.serviceName)
                is MusicLauncher.PlayResult.NoMusicApp ->
                    say(R.string.action_reply_play_no_music_app)
                is MusicLauncher.PlayResult.Failed ->
                    say(R.string.action_reply_play_failed, r.reason)
            }
        }
        return when (val o = mediaTransportController.handle(m)) {
            is MediaTransportController.TransportOutcome.Done -> {
                val f = doneFeedback(o.action, m.level, m.mute)
                when {
                    f.resId == null -> ""
                    f.arg != null -> say(f.resId, f.arg)
                    else -> say(f.resId)
                }
            }
            MediaTransportController.TransportOutcome.NothingPlaying ->
                say(R.string.media_nothing_playing)
            MediaTransportController.TransportOutcome.NeedsPermission -> {
                openNotificationListenerSettings(context)
                say(R.string.media_needs_permission)
            }
            is MediaTransportController.TransportOutcome.Failed -> {
                Log.w(TAG, "media transport failed: ${o.reason}")
                ""
            }
        }
    }

    /**
     * Hands a message off, or sends it outright when the service and the
     * permissions allow. The skill asks for one or the other but can't know
     * which it gets, so the phrasing comes from what actually happened —
     * never from what was requested.
     */
    private fun handleMessage(m: MessageAction): String =
        when (val r = messageLauncher.send(m)) {
            is MessageLauncher.SendResult.Sent ->
                if (m.recipientLabel != null)
                    say(R.string.action_reply_message_sent, m.recipientLabel)
                else
                    say(R.string.action_reply_message_sent_generic)
            is MessageLauncher.SendResult.ReadyToSend ->
                if (m.recipientLabel != null)
                    say(R.string.action_reply_message_ready_to_send, m.recipientLabel)
                else
                    say(R.string.action_reply_message_prepared, r.serviceName)
            is MessageLauncher.SendResult.Prepared ->
                if (m.recipientLabel != null)
                    say(R.string.action_reply_message_prepared_to, r.serviceName, m.recipientLabel)
                else
                    say(R.string.action_reply_message_prepared, r.serviceName)
            MessageLauncher.SendResult.PreparedInChooser ->
                say(R.string.action_reply_message_prepared_chooser)
            is MessageLauncher.SendResult.ServiceNotInstalled ->
                say(R.string.action_reply_message_not_installed, r.serviceName)
            is MessageLauncher.SendResult.Failed -> {
                Log.w(TAG, "message hand-off failed: ${r.reason}")
                say(R.string.action_reply_message_failed)
            }
        }

    /**
     * Answers a live conversation. Every non-success outcome leaves the user
     * able to try again another way, so none of them are phrased as dead ends.
     */
    private fun handleReply(r: ReplyAction): String =
        when (val res = replyLauncher.send(r)) {
            is ReplyLauncher.Result.Sent ->
                say(R.string.action_reply_replied_to, res.recipient)
            ReplyLauncher.Result.NoLiveThread ->
                say(R.string.action_reply_no_live_thread)
            is ReplyLauncher.Result.Ambiguous ->
                say(R.string.action_reply_which_thread, res.names.joinToString(", "))
            ReplyLauncher.Result.NoPermission -> {
                // Same nudge the media transport uses — take them straight to
                // the screen that grants it rather than describing it.
                openNotificationListenerSettings(context)
                say(R.string.action_reply_needs_notification_access)
            }
            is ReplyLauncher.Result.Failed -> {
                Log.w(TAG, "reply failed: ${res.reason}")
                say(R.string.action_reply_reply_failed)
            }
        }

    private fun handleOpenUrl(url: String): String {
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            say(R.string.action_reply_open_url_opening)
        }.getOrElse { t ->
            Log.w(TAG, "open_url failed for $url", t)
            say(R.string.action_reply_open_url_failed)
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService<ClipboardManager>() ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Ari", text))
    }

    /**
     * Resolves [resId] against Ari's *conversation* locale rather than the
     * app/system UI locale. The conversation language (`activeLocale`, e.g.
     * "en"/"it") is exposed synchronously by [AriFfiLocaleProvider]; a fresh
     * configuration context is built per call so a mid-session language change
     * is honoured immediately. Positional args are formatted the same way a
     * plain `getString` would.
     */
    private fun say(resId: Int, vararg args: Any): String {
        val locale = Locale.forLanguageTag(localeProvider.currentLocale())
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val localized = context.createConfigurationContext(config)
        return if (args.isEmpty()) localized.getString(resId)
        else localized.getString(resId, *args)
    }

    companion object {
        private const val TAG = "ActionHandler"
    }
}
