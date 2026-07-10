package dev.heyari.ari.ui.conversation

import dev.heyari.ari.model.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageGroupingTest {
    private fun msg(id: String, user: Boolean, ts: Long) =
        Message(id = id, text = id, isFromUser = user, timestamp = ts)

    @Test fun empty_yields_empty() {
        assertEquals(emptyList<MessageRow>(), MessageGrouping.rows(emptyList()))
    }

    @Test fun single_message_is_first_and_last_and_shows_timestamp() {
        val r = MessageGrouping.rows(listOf(msg("a", true, 0L)))
        assertEquals(1, r.size)
        assertEquals(true, r[0].isFirstInGroup)
        assertEquals(true, r[0].isLastInGroup)
        assertEquals(true, r[0].showTimestamp)
    }

    @Test fun consecutive_same_sender_within_window_group_together() {
        val rows = MessageGrouping.rows(listOf(
            msg("a", true, 0L),
            msg("b", true, 10_000L), // +10s, same sender -> same group
        ))
        assertEquals(true, rows[0].isFirstInGroup);  assertEquals(false, rows[0].isLastInGroup)
        assertEquals(false, rows[1].isFirstInGroup); assertEquals(true, rows[1].isLastInGroup)
    }

    @Test fun sender_switch_breaks_the_group() {
        val rows = MessageGrouping.rows(listOf(
            msg("a", true, 0L),
            msg("b", false, 1_000L),
        ))
        assertEquals(true, rows[0].isLastInGroup)
        assertEquals(true, rows[1].isFirstInGroup)
    }

    @Test fun large_gap_breaks_group_and_shows_timestamp() {
        val rows = MessageGrouping.rows(listOf(
            msg("a", true, 0L),
            msg("b", true, 6 * 60_000L), // +6 min: > group window AND > timestamp threshold
        ))
        assertEquals(true, rows[0].isLastInGroup)
        assertEquals(true, rows[1].isFirstInGroup)
        assertEquals(true, rows[1].showTimestamp)
    }

    @Test fun small_gap_does_not_show_timestamp() {
        val rows = MessageGrouping.rows(listOf(
            msg("a", true, 0L),
            msg("b", false, 30_000L), // +30s: < timestamp threshold
        ))
        assertEquals(false, rows[1].showTimestamp)
    }
}
