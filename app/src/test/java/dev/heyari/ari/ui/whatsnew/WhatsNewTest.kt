package dev.heyari.ari.ui.whatsnew

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    private fun show(seen: Long, updated: Boolean = true, versionName: String = "0.1.2") =
        shouldShowWhatsNew(seen, versionCode = 4, versionName = versionName, notesVersion = "0.1.2", updated = updated)

    @Test
    fun shows_after_an_update_from_a_build_without_the_feature() {
        assertTrue(show(seen = 0))
    }

    @Test
    fun shows_after_an_update_from_an_older_release() {
        assertTrue(show(seen = 3))
    }

    @Test
    fun shows_once_per_version() {
        assertFalse(show(seen = 4))
    }

    @Test
    fun never_on_a_fresh_install() {
        assertFalse(show(seen = 0, updated = false))
    }

    @Test
    fun never_with_notes_written_for_another_release() {
        assertFalse(show(seen = 3, versionName = "0.1.3"))
    }
}
