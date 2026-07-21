package dev.heyari.ari.router

/**
 * Per-locale identity of the FunctionGemma router artifact.
 *
 * CI trains and publishes one model per locale to its own floating release,
 * so the filename, the manifest URL and the on-disk directory all derive
 * from the active locale. There is deliberately no unsuffixed artifact:
 * English is a locale like any other, and [LEGACY_FILENAME] exists only so
 * installs predating this scheme can be migrated.
 */
object RouterModel {
    /** The pre-per-locale filename. Only [RouterLegacyMigration] should read this. */
    const val LEGACY_FILENAME = "ari-functiongemma-q4_k_m.gguf"

    /** The legacy artifact was trained on English, so it can only be adopted as `en`. */
    const val LEGACY_LOCALE = "en"

    fun fileName(locale: String): String = "ari-functiongemma-$locale-q4_k_m.gguf"

    fun manifestUrl(locale: String): String =
        "https://github.com/ari-digital-assistant/ari-tools/releases/download/" +
            "functiongemma-$locale-latest/manifest.json"
}
