package dev.heyari.ari.media

import dev.heyari.ari.actions.MusicLauncher
import uniffi.ari_ffi.FfiMediaServicesProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the FFI [FfiMediaServicesProvider] contract to [MusicLauncher].
 *
 * Returns the list of installed music-service ids so the engine can populate
 * the `available_services` field in the `play_media` skill action.  The real
 * implementation of [MusicLauncher.installedServiceIds] is wired in Task 14;
 * for now it returns an empty list.
 */
@Singleton
class AriFfiMediaServicesProvider @Inject constructor(
    private val musicLauncher: MusicLauncher,
) : FfiMediaServicesProvider {
    override fun `installedServices`(): List<String> = musicLauncher.installedServiceIds()
}
