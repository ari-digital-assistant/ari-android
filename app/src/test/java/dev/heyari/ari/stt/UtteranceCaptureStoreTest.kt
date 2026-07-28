package dev.heyari.ari.stt

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * The sidecar is the half of a capture a human actually reads, so its exact
 * text is the contract: fixed key order, one line per field, and a visible
 * placeholder wherever a layer produced nothing.
 */
class UtteranceCaptureStoreTest {

    private val rescued = UtteranceCapture(
        turn = UtteranceTurn.OPENING,
        outcome = UtteranceOutcome.RESCUED,
        response = "action(dev.heyari.weather)",
        raw = "hey ari whats the weather",
        transcript = "whats the leather",
        parallel = "whats the feather",
        offline = "whats the weather",
        used = "whats the weather",
        locale = "en",
        model = "kroko-en-int8",
    )

    @Test
    fun `a rescued turn records every layer that had a go`() {
        assertEquals(
            """
            when: 2026-07-28T09:15:30Z
            turn: opening
            outcome: rescued
            locale: en
            model: kroko-en-int8
            response: action(dev.heyari.weather)
            raw: hey ari whats the weather
            transcript: whats the leather
            parallel: whats the feather
            offline: whats the weather
            used: whats the weather

            """.trimIndent(),
            utteranceSidecar(rescued, TIMESTAMP_MS, ZoneId.of("UTC")),
        )
    }

    @Test
    fun `the timestamp is rendered in the supplied zone`() {
        val first = utteranceSidecar(rescued, TIMESTAMP_MS, ZoneId.of("Europe/Malta")).lineSequence().first()
        assertEquals("when: 2026-07-28T11:15:30+02:00", first)
    }

    @Test
    fun `layers that produced nothing are marked, not left blank`() {
        val blank = UtteranceCapture(
            turn = UtteranceTurn.REPLY,
            outcome = UtteranceOutcome.BLANK,
            response = null,
            raw = null,
            transcript = "",
            parallel = null,
            offline = null,
            used = "",
            locale = "it",
            model = null,
        )
        assertEquals(
            """
            when: 2026-07-28T09:15:30Z
            turn: reply
            outcome: blank
            locale: it
            model: (none)
            response: (none)
            raw: (none)
            transcript: (none)
            parallel: (none)
            offline: (none)
            used: (none)

            """.trimIndent(),
            utteranceSidecar(blank, TIMESTAMP_MS, ZoneId.of("UTC")),
        )
    }

    private companion object {
        // 2026-07-28T09:15:30Z
        const val TIMESTAMP_MS = 1785230130000L
    }
}
