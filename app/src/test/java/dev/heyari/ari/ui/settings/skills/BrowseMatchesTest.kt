package dev.heyari.ari.ui.settings.skills

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.ari_ffi.FfiBrowseEntry

class BrowseMatchesTest {

    private fun entry(id: String, name: String, type: String = "skill", installed: Boolean = false) =
        FfiBrowseEntry(
            id = id,
            version = "0.1.0",
            name = name,
            description = "",
            skillType = type,
            installed = installed,
            license = null,
            author = null,
            homepage = null,
            capabilities = emptyList(),
            languages = listOf("en"),
            localizations = emptyMap(),
        )

    private val entries = listOf(
        entry("dev.heyari.weather", "Weather", installed = true),
        entry("dev.heyari.timer", "Timer"),
        entry("dev.heyari.assistant.claude", "Claude", type = "assistant"),
    )

    private fun ids(list: List<FfiBrowseEntry>) = list.map { it.id }

    @Test
    fun noFiltersKeepsEverythingInstalledOrNot() {
        assertEquals(ids(entries), ids(browseMatches(entries, typeFilter = null, query = "")))
    }

    @Test
    fun queryMatchesNameOrIdIgnoringCase() {
        assertEquals(listOf("dev.heyari.timer"), ids(browseMatches(entries, null, "TIM")))
        assertEquals(listOf("dev.heyari.assistant.claude"), ids(browseMatches(entries, null, "assistant.cl")))
    }

    @Test
    fun typeFilterKeepsOnlyThatType() {
        assertEquals(listOf("dev.heyari.assistant.claude"), ids(browseMatches(entries, "Assistant", "")))
    }

    @Test
    fun matchesKeepInstalledSkillsSoTheEmptyStateCanTellThemApart() {
        // "Hide installed" is applied after this; a search that only finds an
        // installed skill must still count as a match.
        assertEquals(listOf("dev.heyari.weather"), ids(browseMatches(entries, null, "weather")))
    }
}
