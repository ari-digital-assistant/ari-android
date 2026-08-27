package dev.heyari.ari.model

import java.util.UUID

/** How a user's message reached Ari. Only meaningful for user messages. */
enum class InputSource { Text, Voice }

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
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
)
