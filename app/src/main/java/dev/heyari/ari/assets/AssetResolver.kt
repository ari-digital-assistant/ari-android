package dev.heyari.ari.assets

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a presentation primitive's `asset:<path>` reference to a concrete
 * file inside the emitting skill's bundle directory.
 *
 * Asset URIs are scoped to the skill's bundle. The reference scheme is:
 *
 *   `asset:<path>`  →  `<filesDir>/skills/<skill-dir>/assets/<path>`
 *
 * `<skill-dir>` is the on-disk slug used by `SkillStore`, which today is
 * the directory name from the bundle (typically the skill's manifest
 * `name`, not its full id). Built-in skills don't have a bundle dir and
 * therefore cannot ship assets — `resolve` returns null for them and the
 * caller falls back per primitive (sound → system.notification, icon → no
 * icon).
 *
 * Defence-in-depth: even though the bundle extractor's
 * `is_safe_relative_path` already rejects `..` and absolute paths at
 * install time, this resolver re-checks the resolved file is *inside* the
 * skill's assets dir before returning it. If the file is outside (which
 * shouldn't be possible) or doesn't exist, we return null.
 */
@Singleton
class AssetResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val skillsRoot: File = File(context.filesDir, "skills")

    fun resolve(skillId: String, reference: String?): File? =
        resolveAssetFile(skillsRoot, skillId, reference)

    fun uri(skillId: String, reference: String?): Uri? =
        resolve(skillId, reference)?.let { Uri.fromFile(it) }
}

/**
 * Pure asset-resolution function — extracted as top-level for unit
 * testability without standing up a Hilt graph or an Android Context.
 *
 * Returns null when the [reference] isn't an `asset:<path>` URI, the
 * [skillId] doesn't map to an installed skill dir under [skillsRoot],
 * the resolved file would escape the skill's assets dir, or the file
 * doesn't exist on disk. Defence-in-depth on path escapes — the bundle
 * extractor already rejects `..` at install but we re-check here.
 */
internal fun resolveAssetFile(
    skillsRoot: File,
    skillId: String,
    reference: String?,
): File? {
    if (reference == null) return null
    if (!reference.startsWith(ASSET_PREFIX)) return null
    val path = reference.substring(ASSET_PREFIX.length)
    val skillDir = skillDirFor(skillsRoot, skillId) ?: run {
        Log.w(TAG, "no install dir for skill $skillId; cannot resolve $reference")
        return null
    }
    val assetsDir = File(skillDir, "assets")
    val candidate = File(assetsDir, path).normalize()
    if (!candidate.absolutePath.startsWith(assetsDir.absolutePath)) {
        Log.w(TAG, "asset $reference escapes assets dir for $skillId")
        return null
    }
    if (!candidate.isFile) {
        Log.w(TAG, "asset $reference missing for $skillId at $candidate")
        return null
    }
    return candidate
}

/**
 * The on-disk dir for a skill. Today's `SkillStore` names dirs by the
 * bundle's top-level entry — conventionally the skill's manifest `name`.
 * Built-in Rust skills (open / search / etc.) have no bundle dir and
 * return null; their asset references can't resolve.
 *
 * `dev.heyari.timer` → dir `timer` (segment after last dot). Built-in
 * ids without dots (`open`, `search`, `current_time`) → null.
 */
internal fun skillDirFor(skillsRoot: File, skillId: String): File? {
    if (skillId.isEmpty() || !skillId.contains('.')) return null
    val slug = skillId.substringAfterLast('.')
    val dir = File(skillsRoot, slug)
    return if (dir.isDirectory) dir else null
}

private const val ASSET_PREFIX = "asset:"
private const val TAG = "AssetResolver"
