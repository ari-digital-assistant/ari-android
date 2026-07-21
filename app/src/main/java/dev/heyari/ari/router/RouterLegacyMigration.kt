package dev.heyari.ari.router

import android.util.Log
import dev.heyari.ari.models.InstalledModelMetadata
import java.io.File

enum class LegacyMigrationResult {
    /** The legacy file became the `en` model. No re-download needed. */
    ADOPTED,

    /** The legacy file was deleted without being adopted. */
    DISCARDED,

    /**
     * The move failed. The legacy file is untouched so the next start
     * retries, though an empty `en/` may be left behind.
     */
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
 * frozen artifact forever. An install with nothing to carry gets an explicit
 * `unknown` sidecar instead — same always-stale verdict from the checker, but
 * stated rather than inferred from the file's absence, and crucially it
 * *overwrites* anything already sitting in `en/`. A failed rename in
 * [RouterDownloadManager] deletes the model before it writes, so an
 * `en/version.json` with no GGUF beside it is reachable; inheriting one of
 * those would tell the checker the adopted bytes are current and strand the
 * user on the frozen model permanently. Writing unconditionally makes
 * "adopted ⇒ stale" a local invariant instead of an ordering argument, and
 * costs nothing — the sha is carried, never recomputed.
 */
object RouterLegacyMigration {

    /**
     * Never throws. This runs inline in the engine build, so an escaping
     * exception fails that build — and the build is awaited once and cached,
     * meaning every later `engine()` call rethrows it for the life of the
     * process. The user would lose the whole assistant over a file rename.
     * Trouble degrades to [LegacyMigrationResult.FAILED]; the next start
     * retries from unchanged disk state.
     */
    fun migrate(routerRoot: File, locale: String): LegacyMigrationResult =
        try {
            migrateOrThrow(routerRoot, locale)
        } catch (e: Exception) {
            Log.w(TAG, "legacy router migration failed, will retry next start", e)
            LegacyMigrationResult.FAILED
        }

    private fun migrateOrThrow(routerRoot: File, locale: String): LegacyMigrationResult {
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

        // Past the move the adoption has happened, so nothing below may turn
        // it into a FAILED. A sidecar we couldn't write costs a version
        // string, not the model: with none there it reads back as `unknown`,
        // which is stale to the update checker — the same place a carried-over
        // `r100` puts us, just less specific.
        try {
            val carried = legacyVersion?.files?.firstOrNull()
            InstalledModelMetadata.writeSingle(
                targetDir,
                version = legacyVersion?.version ?: InstalledModelMetadata.UNKNOWN_VERSION,
                fileName = targetFile.name,
                // Nothing verifies a router GGUF against its sidecar sha after
                // install — only the manifest sha at download time — so an
                // empty one when there's nothing to carry costs nothing.
                sha256 = carried?.sha256 ?: "",
            )
            legacySidecar.delete()
        } catch (e: Exception) {
            Log.w(TAG, "legacy sidecar carry-over failed; adopted model reads as unknown version", e)
        }
        Log.i(TAG, "adopted legacy router model as en, version=${legacyVersion?.version}")
        return LegacyMigrationResult.ADOPTED
    }

    private const val TAG = "RouterLegacyMigration"
}
