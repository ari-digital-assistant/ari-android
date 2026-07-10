package dev.heyari.ari.ui.conversation

import dev.heyari.ari.model.Message

data class MessageRow(
    val message: Message,
    val isFirstInGroup: Boolean,
    val isLastInGroup: Boolean,
    val showTimestamp: Boolean,
)

object MessageGrouping {
    /** Consecutive same-sender messages closer than this fold into one group. */
    private const val GROUP_WINDOW_MS = 60_000L
    /** A quiet timestamp divider is shown when the gap to the previous message exceeds this. */
    private const val TIMESTAMP_GAP_MS = 5 * 60_000L

    fun rows(messages: List<Message>): List<MessageRow> {
        if (messages.isEmpty()) return emptyList()
        return messages.mapIndexed { i, m ->
            val prev = messages.getOrNull(i - 1)
            val next = messages.getOrNull(i + 1)
            val breaksFromPrev = prev == null ||
                prev.isFromUser != m.isFromUser ||
                (m.timestamp - prev.timestamp) > GROUP_WINDOW_MS
            val breaksToNext = next == null ||
                next.isFromUser != m.isFromUser ||
                (next.timestamp - m.timestamp) > GROUP_WINDOW_MS
            val showTimestamp = prev == null ||
                (m.timestamp - prev.timestamp) > TIMESTAMP_GAP_MS
            MessageRow(m, isFirstInGroup = breaksFromPrev, isLastInGroup = breaksToNext, showTimestamp = showTimestamp)
        }
    }
}
