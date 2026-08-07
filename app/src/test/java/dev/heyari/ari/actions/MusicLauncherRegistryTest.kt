package dev.heyari.ari.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLauncherRegistryTest {

    @Test
    fun registryCoversAllSixCanonicalIds() {
        val ids = MusicLauncher.REGISTRY.keys
        assertEquals(
            setOf(
                "spotify", "apple_music",
                "tidal", "deezer", "amazon_music",
            ),
            ids,
        )
    }

    @Test
    fun spotifyEntryHasExpectedPackageAndName() {
        val s = MusicLauncher.REGISTRY["spotify"]
        assertNotNull(s)
        assertEquals("Spotify", s!!.displayName)
        assertEquals(listOf("com.spotify.music"), s.packages)
    }

    @Test
    fun youtubeMusicIsRemoved() {
        assertNull(MusicLauncher.REGISTRY["youtube_music"])
    }

    @Test
    fun unknownIdHasNoEntry() {
        assertNull(MusicLauncher.REGISTRY["pandora"])
    }

    @Test
    fun spotifyPrefersPlayFromSearchIntent() {
        assertEquals(
            MusicLauncher.Strategy.PLAY_FROM_SEARCH_INTENT,
            MusicLauncher.REGISTRY["spotify"]!!.strategy.first(),
        )
    }

    @Test
    fun everyRegisteredServiceDeclaresAtLeastOnePackageAndStrategy() {
        // installedServiceIds() filters the registry by isInstalled(pkg),
        // which needs a real PackageManager (Context). We have no Robolectric
        // or Mockito on the unit classpath, so the install-filtering behaviour
        // itself is covered by device e2e (T16). What we CAN assert here without
        // a Context is the precondition installedServiceIds() relies on: every
        // registered service has packages to probe and strategies to dispatch.
        // An entry with no packages would silently never be returned; an entry
        // with no strategy would always Fail. This guards both.
        for ((id, svc) in MusicLauncher.REGISTRY) {
            assertTrue("service '$id' must declare at least one package", svc.packages.isNotEmpty())
            assertTrue("service '$id' must declare at least one strategy", svc.strategy.isNotEmpty())
        }
    }

    // The plain MEDIA_PLAY_FROM_SEARCH intent is honoured by Apple Music (and
    // the rest bar Spotify) by merely OPENING the app — the user asked for
    // music and got a launcher. MEDIA_BROWSER reaches the transport controls
    // and actually plays, so it has to lead for those. Dropping it is what
    // broke "play blinding lights".
    @Test
    fun everyServiceExceptSpotifyLeadsWithMediaBrowser() {
        for ((id, svc) in MusicLauncher.REGISTRY) {
            if (id == "spotify") continue
            assertEquals(
                "service '$id' should lead with MEDIA_BROWSER",
                MusicLauncher.Strategy.MEDIA_BROWSER,
                svc.strategy.first(),
            )
        }
    }

    // Whichever strategy leads, the plain intent must stay as a fallback: an
    // app with no MediaBrowserService, or one that refuses our connection,
    // still has to get the user somewhere.
    @Test
    fun everyServiceKeepsPlayFromSearchIntentAsAFallback() {
        for ((id, svc) in MusicLauncher.REGISTRY) {
            assertTrue(
                "service '$id' must keep PLAY_FROM_SEARCH_INTENT in its chain",
                svc.strategy.contains(MusicLauncher.Strategy.PLAY_FROM_SEARCH_INTENT),
            )
        }
    }
}
