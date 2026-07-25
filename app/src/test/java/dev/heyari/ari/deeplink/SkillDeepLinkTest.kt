package dev.heyari.ari.deeplink

import dev.heyari.ari.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillDeepLinkTest {
    @Test fun `full skill id opens the browse detail`() {
        assertEquals(
            Routes.skillDetail("dev.heyari.weather", "browse"),
            skillDeepLinkRoute("https://heyari.dev/skills/dev.heyari.weather"),
        )
    }

    @Test fun `assistant id works too`() {
        assertEquals(
            Routes.skillDetail("dev.heyari.assistant.chatgpt", "browse"),
            skillDeepLinkRoute("https://heyari.dev/skills/dev.heyari.assistant.chatgpt"),
        )
    }

    @Test fun `trailing slash on an id still resolves`() {
        assertEquals(
            Routes.skillDetail("dev.heyari.timer", "browse"),
            skillDeepLinkRoute("https://heyari.dev/skills/dev.heyari.timer/"),
        )
    }

    @Test fun `bare skills path opens the in-app skills list`() {
        assertEquals(Routes.skills(), skillDeepLinkRoute("https://heyari.dev/skills/"))
        assertEquals(Routes.skills(), skillDeepLinkRoute("https://heyari.dev/skills"))
    }

    @Test fun `wrong host is ignored`() {
        assertNull(skillDeepLinkRoute("https://example.com/skills/dev.heyari.weather"))
    }

    @Test fun `non-skills path is ignored`() {
        assertNull(skillDeepLinkRoute("https://heyari.dev/oauth/callback?code=x"))
    }

    @Test fun `null and garbage are ignored`() {
        assertNull(skillDeepLinkRoute(null))
        assertNull(skillDeepLinkRoute("not a url"))
    }
}
