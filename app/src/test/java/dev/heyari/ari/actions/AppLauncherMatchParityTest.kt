package dev.heyari.ari.actions

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parity guard: AppLauncher.resolve must accept/reject exactly what the engine's
 * Rust `target_matches_app` does (see ari-skills/src/open.rs). The hit/miss table
 * mirrors that skill's `matcher_ladder_hits_and_misses` test.
 */
class AppLauncherMatchParityTest {
    private fun app(label: String, pkg: String) =
        AppLauncher.LaunchableApp(packageName = pkg, label = label)

    private val inventory = listOf(
        app("Spotify", "com.spotify.music"),
        app("Google Chrome", "com.android.chrome"),
        app("Camera", "com.android.camera"),
    )

    @Test fun exact_label() = assertNotNull(AppLauncher.resolve("spotify", inventory))
    @Test fun prefix_label() = assertNotNull(AppLauncher.resolve("goog", inventory))
    @Test fun all_words_any_order() = assertNotNull(AppLauncher.resolve("chrome google", inventory))
    @Test fun exact_camera() = assertNotNull(AppLauncher.resolve("camera", inventory))
    @Test fun device_phrase_misses() = assertNull(AppLauncher.resolve("the main bedroom blinds", inventory))
    @Test fun empty_misses() = assertNull(AppLauncher.resolve("", inventory))
}
