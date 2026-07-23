package dev.heyari.ari.router

import uniffi.ari_ffi.AriEngine

/**
 * The one way to put a router model into the engine. Pairs
 * [AriEngine.loadRouterModel] with [AriEngine.setRouterConfidenceFloor] so
 * the model and the floor it was certified at travel together — a model
 * loaded without its floor runs at the compiled constant, which is exactly
 * the mis-calibration T5 exists to end. Every call site (startup reconcile,
 * settings, update apply) goes through here; loading directly is a bug.
 *
 * [locale] goes to the engine too, not just to the file lookup: the engine
 * refuses to route with a model whose language doesn't match the active one,
 * which closes the window during a language switch where the outgoing
 * model is still resident.
 */
fun AriEngine.loadRouterWithFloor(downloadManager: RouterDownloadManager, locale: String): Boolean {
    val ok = loadRouterModel(downloadManager.modelFile(locale).absolutePath, locale)
    if (ok) setRouterConfidenceFloor(downloadManager.installedMinConfidence(locale))
    return ok
}

/**
 * Unload the router and clear its per-model floor in one motion. A floor
 * with no router is inert, but leaving one behind means the NEXT load that
 * forgets the pairing inherits a dead model's calibration.
 */
fun AriEngine.unloadRouterAndFloor() {
    unloadRouterModel()
    setRouterConfidenceFloor(null)
}
