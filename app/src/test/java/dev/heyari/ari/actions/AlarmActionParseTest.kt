package dev.heyari.ari.actions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmActionParseTest {
    private fun parse(json: String) =
        PresentationEnvelope.parse(JSONObject(json), "dev.heyari.alarm")

    @Test
    fun parses_set_with_days_and_message() {
        val env = parse(
            """{"v":1,"alarm":{"op":"set","hour":7,"minute":0,
               "message":"Wake up","days":["mon","fri"],"skip_ui":true}}"""
        )!!
        val a = env.alarm!!
        assertEquals("set", a.op)
        assertEquals(7, a.hour)
        assertEquals(0, a.minute)
        assertEquals("Wake up", a.message)
        assertEquals(listOf("mon", "fri"), a.days)
    }

    @Test
    fun parses_show_with_null_time() {
        val a = parse("""{"v":1,"alarm":{"op":"show"}}""")!!.alarm!!
        assertEquals("show", a.op)
        assertNull(a.hour)
        assertEquals(emptyList<String>(), a.days)
    }

    @Test
    fun skip_ui_parsed_from_wire() {
        assertTrue(parse("""{"v":1,"alarm":{"op":"set","hour":7,"skip_ui":true}}""")!!.alarm!!.skipUi)
        assertFalse(parse("""{"v":1,"alarm":{"op":"set","hour":7,"skip_ui":false}}""")!!.alarm!!.skipUi)
    }

    @Test
    fun skip_ui_defaults_true_when_absent() {
        assertTrue(parse("""{"v":1,"alarm":{"op":"set","hour":7}}""")!!.alarm!!.skipUi)
    }
}
