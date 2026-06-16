package dev.heyari.ari.location

import uniffi.ari_ffi.FfiLocationProvider
import uniffi.ari_ffi.FfiLocationResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the engine's foreign-callback [FfiLocationProvider] trait to
 * the Android-native [LocationProvider]. Any skill declaring
 * `Capability::Location` and calling `ari::location_current` from WASM
 * ends up here. The mapping is 1:1 — [LocationProvider] already returns
 * the FFI type.
 */
@Singleton
class AriFfiLocationProvider @Inject constructor(
    private val location: LocationProvider,
) : FfiLocationProvider {
    override fun current(maxAgeMs: Long, timeoutMs: Long): FfiLocationResult =
        location.current(maxAgeMs, timeoutMs)
}
