package dev.heyari.ari.actions

import dev.heyari.ari.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFeedbackTest {
    @Test fun transport_verbs_map_to_strings() {
        assertEquals(R.string.media_paused, doneFeedback("pause", null, null).resId)
        assertEquals(R.string.media_resumed, doneFeedback("resume", null, null).resId)
        assertEquals(R.string.media_next, doneFeedback("next", null, null).resId)
        assertEquals(R.string.media_previous, doneFeedback("previous", null, null).resId)
        assertEquals(R.string.media_stopped, doneFeedback("stop", null, null).resId)
    }

    @Test fun volume_set_carries_level_arg() {
        val f = doneFeedback("volume", level = 50, mute = null)
        assertEquals(R.string.media_volume_set, f.resId)
        assertEquals(50, f.arg)
    }

    @Test fun mute_unmute_map_to_strings() {
        assertEquals(R.string.media_muted, doneFeedback("volume", null, mute = true).resId)
        assertEquals(R.string.media_unmuted, doneFeedback("volume", null, mute = false).resId)
    }

    @Test fun volume_up_down_are_silent() {
        // Direction-only volume has no level/mute → nothing spoken (system UI shows).
        assertNull(doneFeedback("volume", null, null).resId)
    }
}
