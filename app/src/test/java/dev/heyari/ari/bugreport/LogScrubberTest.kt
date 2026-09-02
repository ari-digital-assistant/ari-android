package dev.heyari.ari.bugreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogScrubberTest {
    private val secrets = listOf(
        KnownSecret("ha_token", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.abcdefghij.signature"),
        KnownSecret("ha_base_url", "https://ha.vassallo.cloud"),
        KnownSecret("cloud_stt_api_key", "sk-proj-9f2a7c14bd8e"),
    )
    private val scrubber = LogScrubber(secrets)

    @Test
    fun stored_secret_is_replaced_by_its_label() {
        val out = scrubber.scrub("auth sk-proj-9f2a7c14bd8e failed")
        assertEquals("auth <redacted: cloud_stt_api_key> failed", out)
    }

    @Test
    fun stored_url_takes_its_bare_host_with_it() {
        val out = scrubber.scrub("dns lookup for ha.vassallo.cloud timed out")
        assertEquals("dns lookup for <redacted: ha_base_url> timed out", out)
    }

    @Test
    fun full_url_is_redacted_whole_not_just_the_host() {
        val out = scrubber.scrub("POST https://ha.vassallo.cloud/api/states")
        assertEquals("POST <redacted: ha_base_url>/api/states", out)
    }

    @Test
    fun longest_secret_wins_so_no_tail_survives() {
        val nested = LogScrubber(
            listOf(
                KnownSecret("short", "abcd1234"),
                KnownSecret("long", "abcd1234efgh5678"),
            )
        )
        assertEquals("<redacted: long>", nested.scrub("abcd1234efgh5678"))
    }

    @Test
    fun short_stored_values_are_left_alone() {
        val locale = LogScrubber(listOf(KnownSecret("active_locale", "en")))
        assertEquals("engine started, locale en", locale.scrub("engine started, locale en"))
    }

    @Test
    fun credential_keeps_its_field_name() {
        val out = scrubber.scrub("Authorization: Bearer ghp_A1b2C3d4E5f6G7h8")
        assertTrue(out, out.startsWith("Authorization:"))
        assertFalse(out, out.contains("ghp_A1b2C3d4E5f6G7h8"))
    }

    @Test
    fun bare_scheme_without_a_field_name_is_caught() {
        val out = scrubber.scrub("retrying with Bearer ghp_A1b2C3d4E5f6G7h8")
        assertEquals("retrying with Bearer <redacted: credential>", out)
    }

    @Test
    fun credential_in_json_is_caught() {
        val out = scrubber.scrub("""{"api_key":"9f2a7c14bd8e0011","model":"whisper"}""")
        assertFalse(out, out.contains("9f2a7c14bd8e0011"))
        assertTrue(out, out.contains("whisper"))
    }

    @Test
    fun unknown_jwt_is_caught_by_pattern() {
        val out = scrubber.scrub("token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.QWxhZGRpbg")
        assertFalse(out, out.contains("eyJzdWIiOiIxMjM0NSJ9"))
    }

    @Test
    fun email_address_is_removed() {
        assertEquals(
            "signed in as <redacted: email>",
            scrubber.scrub("signed in as keith@icemalta.com"),
        )
    }

    @Test
    fun ip_and_mac_are_removed() {
        val out = scrubber.scrub("peer 192.168.1.44:8123 via AA:BB:CC:DD:EE:FF")
        assertEquals("peer <redacted: ip address> via <redacted: mac address>", out)
    }

    @Test
    fun international_phone_number_is_removed() {
        val out = scrubber.scrub("calling +356 7912 3456 now")
        assertEquals("calling <redacted: phone number> now", out)
    }

    @Test
    fun coordinate_pair_is_removed() {
        val out = scrubber.scrub("geofence at 35.899200, 14.514400 entered")
        assertEquals("geofence at <redacted: coordinates> entered", out)
    }

    @Test
    fun routing_confidence_survives() {
        val line = "FunctionGemma routed to dev.heyari.timer with confidence 0.87342"
        assertEquals(line, scrubber.scrub(line))
    }

    @Test
    fun timestamps_and_versions_survive() {
        val line = "14:08:02 engine 0.7.1 build 1756819682 started in 1243 ms"
        assertEquals(line, scrubber.scrub(line))
    }

    @Test
    fun stack_trace_survives_intact() {
        val trace = """
            java.lang.IllegalStateException: no active session
                at dev.heyari.ari.voice.VoiceSession.stop(VoiceSession.kt:187)
                at dev.heyari.ari.voice.VoiceSession.access${'$'}stop(VoiceSession.kt:42)
        """.trimIndent()
        assertEquals(trace, scrubber.scrub(trace))
    }

    @Test
    fun empty_secret_list_still_applies_patterns() {
        val bare = LogScrubber(emptyList())
        assertEquals("from <redacted: email>", bare.scrub("from keith@icemalta.com"))
    }
}
