package dev.heyari.ari.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerActionTest {
    @Test fun blank_shows_mic() {
        assertEquals(ComposerAction.Mic, composerAction(""))
        assertEquals(ComposerAction.Mic, composerAction("   "))
    }
    @Test fun nonblank_shows_send() {
        assertEquals(ComposerAction.Send, composerAction("hi"))
    }
}
