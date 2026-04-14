package dev.heyari.ari.actions

import dev.heyari.ari.data.timer.Timer
import dev.heyari.ari.model.Attachment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerCoordinatorTest {

    @Test
    fun parsesSpeakSnapshotAndFirstCreateAttachment() {
        val envelope = JSONObject(
            """
            {
              "action": "timer",
              "speak": "Pasta timer set for 8 minutes.",
              "events": [
                {"kind":"create","id":"t_pasta","name":"pasta","duration_ms":480000,"end_ts_ms":1000480000,"created_ts_ms":1000000000}
              ],
              "timers": [
                {"id":"t_pasta","name":"pasta","end_ts_ms":1000480000,"created_ts_ms":1000000000}
              ]
            }
            """.trimIndent(),
        )

        val parsed = parseEnvelope(envelope)

        assertEquals("Pasta timer set for 8 minutes.", parsed.speak)
        assertEquals(
            listOf(Timer(id = "t_pasta", name = "pasta", endTsMs = 1000480000, createdTsMs = 1000000000)),
            parsed.snapshot,
        )
        assertEquals(listOf<Attachment>(Attachment.Timer("t_pasta")), parsed.attachments)
    }

    @Test
    fun missingSnapshotProducesNullNotEmptyList() {
        // Null means "skill didn't include timers, keep local state"; empty
        // list means "skill says there are no timers". The coordinator must
        // distinguish the two or a single malformed envelope would wipe every
        // live timer.
        val envelope = JSONObject("""{"action":"timer","speak":"hi"}""")
        assertNull(parseEnvelope(envelope).snapshot)
    }

    @Test
    fun missingSpeakFallsBackToGenericText() {
        val envelope = JSONObject("""{"action":"timer","timers":[]}""")
        assertEquals("Timer updated.", parseEnvelope(envelope).speak)
    }

    @Test
    fun ackOnlyEnvelopeProducesNoAttachments() {
        val envelope = JSONObject(
            """{"action":"timer","speak":"You have pasta.","events":[{"kind":"ack"}],"timers":[]}""",
        )
        assertTrue(parseEnvelope(envelope).attachments.isEmpty())
    }

    @Test
    fun cancelEventEnvelopeProducesNoAttachments() {
        // Only `create` events surface a card — cancels/acks mutate state
        // silently.
        val envelope = JSONObject(
            """{"action":"timer","speak":"Cancelled.","events":[{"kind":"cancel","id":"t_pasta"}],"timers":[]}""",
        )
        assertTrue(parseEnvelope(envelope).attachments.isEmpty())
    }

    @Test
    fun multipleCreatesAttachOnlyFirstTimer() {
        // One bubble gets one card. The rest of the timers still show in the
        // repository-driven UI strip once that exists; the second create
        // doesn't pile a second card onto the same bubble.
        val envelope = JSONObject(
            """
            {"action":"timer","speak":"Set two.",
             "events":[
               {"kind":"create","id":"t_a","name":null,"duration_ms":1,"end_ts_ms":2,"created_ts_ms":1},
               {"kind":"create","id":"t_b","name":null,"duration_ms":1,"end_ts_ms":2,"created_ts_ms":1}
             ],
             "timers":[]}
            """.trimIndent(),
        )
        assertEquals(listOf<Attachment>(Attachment.Timer("t_a")), parseEnvelope(envelope).attachments)
    }

    @Test
    fun timerWithZeroOrNegativeEndIsRejected() {
        // Defence against skill-side glitches where we'd otherwise propagate
        // a past-expiry timer into the UI and immediately fire an alarm for
        // something that already happened.
        val envelope = JSONObject(
            """
            {"action":"timer","speak":"x","timers":[
              {"id":"t_good","name":null,"end_ts_ms":100,"created_ts_ms":1},
              {"id":"t_bad","name":null,"end_ts_ms":0,"created_ts_ms":1}
            ]}
            """.trimIndent(),
        )
        val snapshot = parseEnvelope(envelope).snapshot!!
        assertEquals(listOf("t_good"), snapshot.map { it.id })
    }

    @Test
    fun timerWithNullNameIsPreservedAsNull() {
        val envelope = JSONObject(
            """{"action":"timer","speak":"x","timers":[
                 {"id":"t_anon","name":null,"end_ts_ms":10,"created_ts_ms":1}
               ]}""",
        )
        val snapshot = parseEnvelope(envelope).snapshot!!
        assertEquals(null, snapshot.single().name)
    }

    @Test
    fun diffScheduleNewTimer() {
        val prev = emptyList<Timer>()
        val curr = listOf(Timer("t1", "pasta", endTsMs = 100, createdTsMs = 0))
        assertEquals(listOf(AlarmWork.Schedule(curr[0])), diffAlarmWork(prev, curr))
    }

    @Test
    fun diffCancelDroppedTimer() {
        val prev = listOf(Timer("t1", "pasta", endTsMs = 100, createdTsMs = 0))
        val curr = emptyList<Timer>()
        assertEquals(listOf(AlarmWork.Cancel("t1")), diffAlarmWork(prev, curr))
    }

    @Test
    fun diffUnchangedTimerRescheduledForIdempotency() {
        // FLAG_UPDATE_CURRENT means re-scheduling is cheap; we prefer a
        // single code path ("every current timer gets scheduled") over
        // introducing branches that can silently miss an updated end_ts.
        val t = Timer("t1", "pasta", endTsMs = 100, createdTsMs = 0)
        val diff = diffAlarmWork(listOf(t), listOf(t))
        assertEquals(listOf(AlarmWork.Schedule(t)), diff)
    }

    @Test
    fun diffMixedAddAndRemove() {
        val prev = listOf(
            Timer("t1", "pasta", endTsMs = 100, createdTsMs = 0),
            Timer("t2", "egg", endTsMs = 200, createdTsMs = 0),
        )
        val curr = listOf(
            Timer("t2", "egg", endTsMs = 200, createdTsMs = 0),
            Timer("t3", "bread", endTsMs = 300, createdTsMs = 0),
        )
        val diff = diffAlarmWork(prev, curr)
        assertEquals(
            listOf(
                AlarmWork.Cancel("t1"),
                AlarmWork.Schedule(curr[0]),
                AlarmWork.Schedule(curr[1]),
            ),
            diff,
        )
    }
}
