package dev.heyari.ari.messaging

import android.app.Activity
import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a send is reported as, from the sent-status broadcast.
 *
 * Before there was one, `send` returned Sent whenever the call didn't throw —
 * so a text to a number that couldn't receive it came back a success. These pin
 * the two ways that must not regress: a real error is never called sent, and a
 * silence is never called failed.
 */
class SmsOutcomeTest {

    @Test
    fun everySegmentReportingOkIsASend() {
        assertEquals(
            SmsSender.Result.Sent,
            SmsSender.outcomeOf(settled = true, worstCode = Activity.RESULT_OK),
        )
    }

    @Test
    fun anErrorCodeIsNotASend() {
        val r = SmsSender.outcomeOf(settled = true, worstCode = SmsManager.RESULT_ERROR_NO_SERVICE)
        assertEquals(SmsSender.Result.NotSent("no service"), r)
    }

    @Test
    fun eachKnownFailureSaysWhat() {
        fun reason(code: Int) =
            (SmsSender.outcomeOf(settled = true, worstCode = code) as SmsSender.Result.NotSent).reason
        assertEquals("no service", reason(SmsManager.RESULT_ERROR_NO_SERVICE))
        assertEquals("radio off", reason(SmsManager.RESULT_ERROR_RADIO_OFF))
        assertEquals("null pdu", reason(SmsManager.RESULT_ERROR_NULL_PDU))
        assertEquals("limit exceeded", reason(SmsManager.RESULT_ERROR_LIMIT_EXCEEDED))
    }

    @Test
    fun anUnrecognisedCodeStillCarriesItsNumber() {
        val r = SmsSender.outcomeOf(settled = true, worstCode = 99) as SmsSender.Result.NotSent
        assertEquals("error 99", r.reason)
    }

    @Test
    fun silenceIsNotFailure() {
        // The modem has the message and it may well arrive. Calling this failed
        // would send the user off to compose a duplicate.
        assertEquals(
            SmsSender.Result.Sent,
            SmsSender.outcomeOf(settled = false, worstCode = Activity.RESULT_OK),
        )
        // Even if a segment had already errored, an unfinished set is not a
        // verdict — the deadline is there to stop blocking the turn, nothing more.
        assertEquals(
            SmsSender.Result.Sent,
            SmsSender.outcomeOf(settled = false, worstCode = SmsManager.RESULT_ERROR_NO_SERVICE),
        )
    }

    @Test
    fun aRefusalIsItsOwnOutcomeNotAFailedAttempt() {
        // Failed means the send was never attempted, and the two lead to
        // different words. Collapsing them would lose that.
        val r = SmsSender.outcomeOf(settled = true, worstCode = SmsManager.RESULT_ERROR_RADIO_OFF)
        assertTrue(r is SmsSender.Result.NotSent)
        assertTrue(r !is SmsSender.Result.Failed)
    }
}
