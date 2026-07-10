package dev.heyari.ari.actions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigateActionParseTest {
    private fun parse(json: String) =
        PresentationEnvelope.parse(JSONObject(json), "dev.heyari.navigation")

    @Test
    fun parses_destination_and_mode() {
        val env = parse(
            """{"v":1,"navigate":{"destination":"mcdonalds","mode":"turn_by_turn"}}"""
        )!!
        val n = env.navigate!!
        assertEquals("mcdonalds", n.destination)
        assertEquals("turn_by_turn", n.mode)
    }

    @Test
    fun parses_null_mode_when_absent() {
        val n = parse("""{"v":1,"navigate":{"destination":"asda"}}""")!!.navigate!!
        assertEquals("asda", n.destination)
        assertNull(n.mode)
    }

    @Test
    fun navigate_absent_yields_null() {
        val env = parse("""{"v":1,"speak":"hi"}""")!!
        assertNull(env.navigate)
    }
}
