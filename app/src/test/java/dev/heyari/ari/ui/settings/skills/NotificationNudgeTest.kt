package dev.heyari.ari.ui.settings.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationNudgeTest {

    @Test
    fun replyCapabilityEarnsTheReplyNudge() {
        // The bug this exists for: the message skill declares `reply`, needs
        // notification access to do anything with it, and used to install
        // without ever being asked.
        assertEquals(
            NotificationNudge.Reply,
            NotificationNudge.forCapabilities(
                listOf("send_message", "contacts", "http", "reply"),
            ),
        )
    }

    @Test
    fun mediaControlEarnsTheMediaNudge() {
        assertEquals(
            NotificationNudge.Media,
            NotificationNudge.forCapabilities(listOf("media_control")),
        )
    }

    @Test
    fun mediaWinsWhenASkillWantsBoth() {
        assertEquals(
            NotificationNudge.Media,
            NotificationNudge.forCapabilities(listOf("reply", "media_control")),
        )
    }

    @Test
    fun capabilityNamesAreMatchedCaseInsensitively() {
        assertEquals(
            NotificationNudge.Reply,
            NotificationNudge.forCapabilities(listOf("Reply")),
        )
    }

    @Test
    fun nothingNeedingNotificationsAsksForNothing() {
        assertNull(NotificationNudge.forCapabilities(listOf("location", "contacts")))
        assertNull(NotificationNudge.forCapabilities(emptyList()))
    }
}
