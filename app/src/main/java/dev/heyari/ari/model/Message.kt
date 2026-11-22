package dev.heyari.ari.model

import java.util.UUID

/** How a user's message reached Ari. Only meaningful for user messages. */
enum class InputSource { Text, Voice }

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    /**
     * An assistant answer as the model wrote it, for the bubble to render
     * (ari-android#57); [text] is the same answer as plain text, for speaking
     * and plain copying. Null for everything else, and for an answer that
     * formats nothing.
     */
    val markdown: String? = null,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<Attachment> = emptyList(),
    val source: InputSource = InputSource.Text,
    /**
     * Which skill produced an Ari message, for content reports to name.
     *
     * Null for user messages and for every plain-text answer: `FfiResponse.Text`
     * carries no id, so only action responses can be attributed. A report on an
     * unattributed turn says so rather than guessing.
     */
    val skillId: String? = null,
    /**
     * A note about the chat itself rather than a turn in it, shown centred and
     * faded: so far only that Ari has forgotten the conversation above
     * ([dev.heyari.ari.data.conversation.ConversationLapseWatcher]). Always
     * with [isFromUser] false.
     */
    val isNote: Boolean = false,
)
