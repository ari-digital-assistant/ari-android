package dev.heyari.ari.actions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageActionParseTest {
    private fun parse(json: String) =
        PresentationEnvelope.parse(JSONObject(json), "dev.heyari.message")

    @Test
    fun parses_a_full_message_slot() {
        val m = parse(
            """{"v":1,"message":{"service":"whatsapp","recipient_id":"35699000000",
               "recipient_label":"Mario","text":"I'll be home soon","delivery":"compose"}}"""
        )!!.message!!
        assertEquals("whatsapp", m.service)
        assertEquals("35699000000", m.recipientId)
        assertEquals("Mario", m.recipientLabel)
        assertEquals("I'll be home soon", m.text)
        assertEquals("compose", m.delivery)
    }

    @Test
    fun text_is_the_only_required_field() {
        val m = parse("""{"v":1,"message":{"text":"on my way"}}""")!!.message!!
        assertEquals("on my way", m.text)
        assertNull(m.service)
        assertNull(m.recipientId)
        assertNull(m.recipientLabel)
        assertNull(m.delivery)
    }

    @Test
    fun a_message_without_text_is_dropped() {
        val env = parse("""{"v":1,"message":{"service":"whatsapp","recipient_label":"Mario"}}""")!!
        assertNull(env.message)
    }

    @Test
    fun message_absent_yields_null() {
        assertNull(parse("""{"v":1,"speak":"hi"}""")!!.message)
    }

    @Test
    fun capitals_and_apostrophes_survive_the_wire() {
        // The whole point of raw_input reaching the skill: what the recipient
        // reads must be what the user said, not the normalised form.
        val m = parse("""{"v":1,"message":{"text":"I'll be home soon"}}""")!!.message!!
        assertEquals("I'll be home soon", m.text)
    }

    @Test
    fun aReplySlotParses() {
        val r = parse("""{"v":1,"reply":{"recipient_label":"Gail","text":"On my way"}}""")!!.reply!!
        assertEquals("Gail", r.recipientLabel)
        assertEquals("On my way", r.text)
    }

    @Test
    fun aReplyWithoutARecipientMeansTheNewestThread() {
        // "reply, on my way" — the hands-free case.
        val r = parse("""{"v":1,"reply":{"text":"On my way"}}""")!!.reply!!
        assertNull(r.recipientLabel)
        assertEquals("On my way", r.text)
    }

    @Test
    fun aReplyWithoutTextIsDropped() {
        assertNull(parse("""{"v":1,"reply":{"recipient_label":"Gail"}}""")!!.reply)
    }

    @Test
    fun replyAbsentYieldsNull() {
        assertNull(parse("""{"v":1,"speak":"hi"}""")!!.reply)
    }
}