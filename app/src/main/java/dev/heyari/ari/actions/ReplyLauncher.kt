package dev.heyari.ari.actions

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.media.hasNotificationAccess
import dev.heyari.ari.messaging.LiveConversations
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers a conversation that still has a notification showing.
 *
 * The only transport here that reaches somebody with nobody touching the
 * screen — no compose box, no tap. It works on every messenger supporting
 * notification replies, with no per-service code, because the reply action
 * comes out of the notification itself rather than out of a catalogue.
 *
 * The bound is the notification. Once it's dismissed the thread is gone from
 * here and composing is the answer, which is why every failure below leaves
 * the caller free to fall back rather than reporting a dead end.
 */
@Singleton
class ReplyLauncher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val live: LiveConversations,
) {
    sealed interface Result {
        /** Gone, into the named thread. */
        data class Sent(val recipient: String) : Result

        /** Nothing open to answer. The caller should compose instead. */
        data object NoLiveThread : Result

        /** Several match; guessing which thread is as bad as guessing which
         *  contact, and just as unrecallable. */
        data class Ambiguous(val names: List<String>) : Result

        /** Notification access hasn't been granted. Not a failure — the user
         *  hasn't been asked yet. */
        data object NoPermission : Result

        data class Failed(val reason: String) : Result
    }

    fun send(action: ReplyAction): Result {
        if (action.text.isBlank()) return Result.Failed("empty reply")
        if (!hasNotificationAccess(context)) return Result.NoPermission

        return when (val m = live.match(action.recipientLabel, System.currentTimeMillis())) {
            LiveConversations.Match.None -> Result.NoLiveThread
            is LiveConversations.Match.Several -> Result.Ambiguous(m.titles)
            is LiveConversations.Match.One ->
                if (live.reply(context, m.conversation, action.text)) {
                    Result.Sent(m.conversation.title)
                } else {
                    // The notification was dismissed between us reading it and
                    // firing — the thread moved on under us.
                    Log.w(TAG, "reply intent would not fire for ${m.conversation.serviceId}")
                    Result.NoLiveThread
                }
        }
    }

    private companion object {
        const val TAG = "ReplyLauncher"
    }
}
