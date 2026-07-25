package dev.heyari.ari.actions

import uniffi.ari_ffi.AriEngine
import uniffi.ari_ffi.FfiAppEntry

/** Maps launchable apps to the FFI record the engine's `open` skill scores against. */
fun List<AppLauncher.LaunchableApp>.toFfiAppEntries(): List<FfiAppEntry> =
    map { FfiAppEntry(label = it.label, `package` = it.packageName) }

/** Pushes the current launchable-app inventory into the engine. */
fun AriEngine.pushInstalledApps(appLauncher: AppLauncher) {
    setInstalledApps(appLauncher.listLaunchable().toFfiAppEntries())
}
