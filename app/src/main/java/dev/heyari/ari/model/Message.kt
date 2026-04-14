package dev.heyari.ari.model

import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<Attachment> = emptyList(),
)
