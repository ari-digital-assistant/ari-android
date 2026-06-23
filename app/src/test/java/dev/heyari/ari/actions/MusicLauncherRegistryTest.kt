package dev.heyari.ari.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MusicLauncherRegistryTest {

    @Test
    fun registryCoversAllSevenCanonicalIds() {
        val ids = MusicLauncher.REGISTRY.keys
        assertEquals(
            setOf(
                "spotify", "apple_music", "youtube_music",
                "tidal", "deezer", "youtube", "amazon_music",
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
    fun youtubeMusicEntryDistinctFromYoutube() {
        assertEquals(
            listOf("com.google.android.apps.youtube.music"),
            MusicLauncher.REGISTRY["youtube_music"]!!.packages,
        )
        assertEquals(
            listOf("com.google.android.youtube"),
            MusicLauncher.REGISTRY["youtube"]!!.packages,
        )
    }

    @Test
    fun unknownIdHasNoEntry() {
        assertNull(MusicLauncher.REGISTRY["pandora"])
    }
}
