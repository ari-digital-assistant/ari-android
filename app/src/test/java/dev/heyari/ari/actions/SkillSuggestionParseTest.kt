package dev.heyari.ari.actions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine's `suggest_skill` primitive (ari-android#44). */
class SkillSuggestionParseTest {
    private fun parse(json: String) = PresentationEnvelope.parse(JSONObject(json), "")!!

    @Test
    fun parses_the_suggested_skill_and_counts_as_presentation() {
        val env = parse(
            """{"v":1,"speak":"The Reminder skill can do that. Want to add it?",
               "suggest_skill":{"id":"dev.heyari.reminder","name":"Reminder"}}"""
        )
        assertEquals(SkillSuggestion("dev.heyari.reminder", "Reminder"), env.suggestSkill)
        assertEquals("The Reminder skill can do that. Want to add it?", env.speak)
        // Otherwise the coordinator never runs and the button never shows.
        assertTrue(env.hasPresentationPrimitives())
    }

    @Test
    fun name_falls_back_to_the_id() {
        val env = parse("""{"v":1,"suggest_skill":{"id":"dev.heyari.reminder"}}""")
        assertEquals("dev.heyari.reminder", env.suggestSkill!!.name)
    }

    @Test
    fun absent_or_without_an_id_is_no_suggestion() {
        assertNull(parse("""{"v":1,"speak":"hi"}""").suggestSkill)
        assertNull(parse("""{"v":1,"suggest_skill":{"name":"Reminder"}}""").suggestSkill)
        assertFalse(parse("""{"v":1,"speak":"hi"}""").hasPresentationPrimitives())
    }
}
