package dev.heyari.ari.messaging

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.RemoteInput
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A conversation that can be replied into right now.
 *
 * Deliberately does **not** hold the message. What arrived is somebody else's
 * words to the user; Ari needs to know who it was from and how to answer, and
 * keeping the body would be retaining other people's correspondence for no
 * reason it could justify.
 */
class LiveConversation(
    /** The notification's own key, so removal can find this entry again. */
    val key: String,
    val serviceId: String,
    /** Who the thread is with, as their own app displays it. */
    val title: String,
    val postedAtMs: Long,
    internal val action: Notification.Action,
)

/**
 * Live messaging conversations, kept only so Ari can reply into one.
 *
 * A notification listener sees every notification on the device — banking,
 * health, two-factor codes. That is a far larger surface than anything else
 * Ari touches, so the filtering happens on the way *in*: a notification from a
 * package that isn't a known messaging service never enters this store, and no
 * general record of notifications exists anywhere to leak.
 *
 * What is retained per conversation is the minimum that can fire a reply — who
 * it's with, and the action to answer with. Never the message.
 *
 * Entries leave on dismissal and on a TTL. A [PendingIntent] for a thread the
 * user finished with is a reply waiting to go somewhere wrong.
 */
@Singleton
class LiveConversations @Inject constructor() {
    private val entries = LinkedHashMap<String, LiveConversation>()

    /**
     * Record a notification if it's a messaging conversation we could answer.
     * Returns true when it was kept, which is what the tests assert on.
     */
    @Synchronized
    fun offer(
        key: String,
        packageName: String,
        title: String?,
        postedAtMs: Long,
        notification: Notification,
        catalogue: Map<String, MessagingService>,
    ): Boolean {
        val service = serviceFor(packageName, catalogue) ?: return false
        val name = title?.trim().orEmpty()
        if (name.isEmpty()) return false
        val action = replyAction(notification) ?: return false
        entries[key] = LiveConversation(key, service.id, name, postedAtMs, action)
        return true
    }

    @Synchronized
    fun forget(key: String) {
        entries.remove(key)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** Live conversations, most recently posted first. */
    @Synchronized
    fun current(nowMs: Long): List<LiveConversation> {
        entries.values.removeAll { isStale(it.postedAtMs, nowMs) }
        return entries.values.sortedByDescending { it.postedAtMs }
    }

    /** Display names only — this is all that ever crosses into the engine. */
    fun names(nowMs: Long): List<String> = current(nowMs).map { it.title }

    /**
     * The conversation a spoken name meant, or the newest when no name was
     * given — "reply, on my way" while driving is the case this exists for.
     *
     * Refuses to choose between two matches. Sending to the wrong thread is
     * exactly as unrecallable as sending to the wrong contact.
     */
    fun match(name: String?, nowMs: Long): Match {
        val live = current(nowMs)
        return when (val picked = choose(name, live.map { it.title })) {
            is Choice.One -> Match.One(live[picked.index])
            is Choice.Several -> Match.Several(picked.titles)
            Choice.None -> Match.None
        }
    }

    sealed interface Match {
        data class One(val conversation: LiveConversation) : Match
        data class Several(val titles: List<String>) : Match
        data object None : Match
    }

    /** [choose]'s answer, by index into the list it was given. */
    sealed interface Choice {
        data class One(val index: Int) : Choice
        data class Several(val titles: List<String>) : Choice
        data object None : Choice
    }

    /** Fire a reply into [conversation]. */
    fun reply(context: Context, conversation: LiveConversation, text: String): Boolean {
        val action = conversation.action
        val inputs = action.remoteInputs?.takeIf { it.isNotEmpty() } ?: return false
        return try {
            val results = Bundle()
            for (input in inputs) {
                results.putCharSequence(input.resultKey, text)
            }
            val intent = Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            RemoteInput.addResultsToIntent(
                inputs.map { legacy ->
                    RemoteInput.Builder(legacy.resultKey)
                        .setAllowFreeFormInput(true)
                        .build()
                }.toTypedArray(),
                intent,
                results,
            )
            action.actionIntent.send(context, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            // The conversation moved on — the notification was dismissed
            // between us reading it and replying.
            Log.w(TAG, "reply intent had been cancelled", e)
            forget(conversation.key)
            false
        }
    }

    companion object {
        private const val TAG = "LiveConversations"

        /**
         * How long a conversation stays repliable. Long enough to cover
         * "message arrives, user finishes what they're doing, answers";
         * short enough that a `PendingIntent` doesn't linger for a thread
         * the user has mentally closed.
         */
        const val TTL_MS: Long = 30 * 60 * 1000

        fun isStale(postedAtMs: Long, nowMs: Long): Boolean = nowMs - postedAtMs > TTL_MS

        /** The catalogue entry that owns [packageName], if any. This is the
         *  filter that keeps every non-messaging notification out. */
        fun serviceFor(
            packageName: String,
            catalogue: Map<String, MessagingService>,
        ): MessagingService? = catalogue.values.firstOrNull { it.packages.contains(packageName) }

        /**
         * Which of [titles] a spoken name meant, most-recent-first order
         * assumed. Pure, so the choosing can be tested without a framework.
         *
         * No name means the newest thread — "reply, on my way" while driving
         * is the case this exists for. Two matches are refused rather than
         * guessed between: a reply into the wrong thread is exactly as
         * unrecallable as a message to the wrong contact.
         */
        fun choose(name: String?, titles: List<String>): Choice {
            if (titles.isEmpty()) return Choice.None
            val wanted = name?.trim()?.lowercase().orEmpty()
            if (wanted.isEmpty()) return Choice.One(0)

            val exact = titles.withIndex().filter { it.value.lowercase() == wanted }
            if (exact.size == 1) return Choice.One(exact.first().index)
            if (exact.size > 1) return Choice.Several(exact.map { it.value })

            val hits = titles.withIndex().filter { it.value.lowercase().contains(wanted) }
            return when {
                hits.isEmpty() -> Choice.None
                hits.size == 1 -> Choice.One(hits.first().index)
                else -> Choice.Several(hits.map { it.value })
            }
        }

        /** The first action carrying free-form `RemoteInput` — i.e. a reply
         *  box rather than "Mark as read". */
        fun replyAction(notification: Notification): Notification.Action? =
            notification.actions?.firstOrNull { a ->
                a.remoteInputs?.any { it.allowFreeFormInput } == true
            }
    }
}
