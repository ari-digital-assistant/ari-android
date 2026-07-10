package dev.heyari.ari.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmptyStateLogicTest {
    @Test fun finds_name_in_canonical_fact() {
        assertEquals("Keith", detectUserName(listOf("i like pasta", "my name is Keith")))
    }
    @Test fun handles_the_users_name_is_phrasing() {
        assertEquals("Keith", detectUserName(listOf("the user's name is Keith")))
    }
    @Test fun handles_call_me() {
        assertEquals("Kez", detectUserName(listOf("call me Kez")))
    }
    @Test fun handles_im() {
        assertEquals("Keith", detectUserName(listOf("i'm Keith")))
    }
    @Test fun no_name_returns_null() {
        assertNull(detectUserName(listOf("i like pasta", "i work at ICE")))
    }
    @Test fun empty_returns_null() { assertNull(detectUserName(emptyList())) }

    @Test fun anonymous_when_no_name() =
        assertEquals(GreetingModel.Anonymous, greetingModel(null, 9))
    @Test fun morning_named() =
        assertEquals(GreetingModel.Named(DayPart.MORNING, "Keith"), greetingModel("Keith", 9))
    @Test fun afternoon_named() =
        assertEquals(GreetingModel.Named(DayPart.AFTERNOON, "Keith"), greetingModel("Keith", 14))
    @Test fun evening_named() =
        assertEquals(GreetingModel.Named(DayPart.EVENING, "Keith"), greetingModel("Keith", 21))

    @Test fun one_example_per_skill_capped() {
        val out = assembleChips(
            skillExamples = listOf(listOf("what's the weather?", "weather in tokyo"), listOf("set a reminder")),
            rememberNameChip = null, max = 4,
        )
        assertEquals(listOf("what's the weather?", "set a reminder"), out)
    }
    @Test fun remember_name_chip_leads_when_present() {
        val out = assembleChips(
            skillExamples = listOf(listOf("set a reminder")),
            rememberNameChip = "Remember my name", max = 4,
        )
        assertEquals(listOf("Remember my name", "set a reminder"), out)
    }
    @Test fun respects_max() {
        val out = assembleChips(
            skillExamples = listOf(listOf("a"), listOf("b"), listOf("c")),
            rememberNameChip = "R", max = 2,
        )
        assertEquals(listOf("R", "a"), out)
    }

    @Test fun zero_skills_is_first_run() = assertEquals(EmptyMode.FirstRun, emptyStateMode(0))
    @Test fun one_skill_is_set_up() = assertEquals(EmptyMode.SetUp, emptyStateMode(1))
    @Test fun many_skills_is_set_up() = assertEquals(EmptyMode.SetUp, emptyStateMode(6))
}
