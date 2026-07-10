package dev.heyari.ari.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationLauncherTest {
    @Test
    fun turn_by_turn_tries_google_then_geo() {
        assertEquals(
            listOf("google.navigation:q=San%20Francisco", "geo:0,0?q=San%20Francisco"),
            NavigationLauncher.navUris("San%20Francisco", "turn_by_turn"),
        )
    }

    @Test
    fun default_app_uses_geo_only() {
        assertEquals(
            listOf("geo:0,0?q=asda"),
            NavigationLauncher.navUris("asda", "default_app"),
        )
    }

    @Test
    fun null_mode_defaults_to_geo() {
        assertEquals(
            listOf("geo:0,0?q=home"),
            NavigationLauncher.navUris("home", null),
        )
    }
}
