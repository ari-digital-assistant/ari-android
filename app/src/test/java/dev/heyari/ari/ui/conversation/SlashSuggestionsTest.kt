package dev.heyari.ari.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.ari_ffi.FfiSlashCommand

class SlashSuggestionsTest {
    private val clear = FfiSlashCommand("clear", "Clear the conversation and start over", false)
    private val remember = FfiSlashCommand("remember", "Remember something about you", true)
    private val recall = FfiSlashCommand("recall", "What I remember about you", false)
    private val commands = listOf(clear, remember, recall)

    @Test fun a_bare_slash_offers_every_command() {
        assertEquals(commands, slashSuggestions("/", commands))
    }

    @Test fun typing_narrows_the_list_by_name() {
        assertEquals(listOf(remember, recall), slashSuggestions("/re", commands))
        assertEquals(listOf(recall), slashSuggestions("/rec", commands))
        assertEquals(listOf(remember), slashSuggestions("/REM", commands))
    }

    @Test fun a_finished_name_still_shows_its_command() {
        assertEquals(listOf(clear), slashSuggestions("/clear", commands))
    }

    @Test fun nothing_matches_an_unknown_name() {
        assertEquals(emptyList<FfiSlashCommand>(), slashSuggestions("/xyz", commands))
    }

    @Test fun no_list_once_the_argument_starts() {
        assertEquals(emptyList<FfiSlashCommand>(), slashSuggestions("/remember ", commands))
        assertEquals(emptyList<FfiSlashCommand>(), slashSuggestions("/remember i like tea", commands))
    }

    @Test fun no_list_without_a_leading_slash() {
        assertEquals(emptyList<FfiSlashCommand>(), slashSuggestions("", commands))
        assertEquals(emptyList<FfiSlashCommand>(), slashSuggestions("remember", commands))
        assertEquals(emptyList<FfiSlashCommand>(), slashSuggestions("8/2", commands))
    }
}
