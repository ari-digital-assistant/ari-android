package dev.heyari.ari.router

import android.util.Log
import dev.heyari.ari.models.InstalledModelMetadata
import java.io.File

enum class LegacyMigrationResult {
    /** The legacy file became the `en` model. No re-download needed. */
    ADOPTED,

    /** The legacy file was deleted without being adopted. */
    DISCARDED,

    /** The move failed; nothing was changed, so the next start retries. */
    FAILED,

    /** No legacy install present. */
    NOTHING_TO_DO,
}

/**
 * One-shot adoption of a router install predating per-locale models.
 *
 * Installs from before this scheme have an unsuffixed GGUF sitting directly
 * in `models/router/`. It's a perfectly good English model, so English
 * installs move it into `models/router/en/` under the new name rather than
 * re-pulling 253 MB — the router never stops working across the update. Any
 * other locale deletes it instead: the one thing we can't do is file an
 * English model under a locale it wasn't trained for.
 *
 * The carried-over sidecar version matters. Keeping `r100` is what makes the
 * update checker see the adopted file as stale and offer the real `-en-`
 * model; inventing a current-looking version would strand the user on the
 * frozen artifact forever.
 */
object RouterLegacyMigration {

    fun migrate(routerRoot: File, locale: String): LegacyMigrationResult {
        val legacyFile = File(routerRoot, RouterModel.LEGACY_FILENAME)
        if (!legacyFile.isFile) return LegacyMigrationResult.NOTHING_TO_DO

        val legacySidecar = File(routerRoot, InstalledModelMetadata.SIDECAR_FILENAME)
        val targetDir = File(routerRoot, RouterModel.LEGACY_LOCALE)
        val targetFile = File(targetDir, RouterModel.fileName(RouterModel.LEGACY_LOCALE))

        if (locale != RouterModel.LEGACY_LOCALE || targetFile.isFile) {
            legacySidecar.delete()
            legacyFile.delete()
            Log.i(TAG, "discarded legacy router model (locale=$locale)")
            return LegacyMigrationResult.DISCARDED
        }

        val legacyVersion = InstalledModelMetadata.read(routerRoot)

        targetDir.mkdirs()
        if (!legacyFile.renameTo(targetFile)) {
            Log.w(TAG, "legacy router move failed, will retry next start")
            return LegacyMigrationResult.FAILED
        }

        InstalledModelMetadata.writeSingle(
            targetDir,
            version = legacyVersion?.version ?: InstalledModelMetadata.UNKNOWN_VERSION,
            fileName = targetFile.name,
            sha256 = legacyVersion?.files?.firstOrNull()?.sha256
                ?: InstalledModelMetadata.sha256Hex(targetFile),
        )
        legacySidecar.delete()
        Log.i(TAG, "adopted legacy router model as en, version=${legacyVersion?.version}")
        return LegacyMigrationResult.ADOPTED
    }

    private const val TAG = "RouterLegacyMigration"
}
