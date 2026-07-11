package dev.heyari.ari.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerActionTest {
    @Test fun blank_and_not_dictating_shows_mic() {
        assertEquals(ComposerAction.Mic, composerAction("", isDictating = false))
        assertEquals(ComposerAction.Mic, composerAction("   ", isDictating = false))
    }
    @Test fun nonblank_and_not_dictating_shows_send() {
        assertEquals(ComposerAction.Send, composerAction("hi", isDictating = false))
    }
    @Test fun dictating_shows_stop_regardless_of_text() {
        assertEquals(ComposerAction.Stop, composerAction("", isDictating = true))
        assertEquals(ComposerAction.Stop, composerAction("live partial words", isDictating = true))
    }
}
