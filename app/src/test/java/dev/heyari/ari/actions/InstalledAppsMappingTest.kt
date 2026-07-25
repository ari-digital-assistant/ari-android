package dev.heyari.ari.actions

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.ari_ffi.FfiAppEntry

class InstalledAppsMappingTest {
    @Test
    fun maps_label_and_package() {
        val apps = listOf(
            AppLauncher.LaunchableApp(packageName = "com.spotify.music", label = "Spotify"),
            AppLauncher.LaunchableApp(packageName = "com.android.chrome", label = "Google Chrome"),
        )
        val entries = apps.toFfiAppEntries()
        assertEquals(
            listOf(
                FfiAppEntry(label = "Spotify", `package` = "com.spotify.music"),
                FfiAppEntry(label = "Google Chrome", `package` = "com.android.chrome"),
            ),
            entries,
        )
    }
}
