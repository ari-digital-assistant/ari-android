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
)
