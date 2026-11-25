package dev.heyari.ari.assets

import android.net.Uri
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.SkillRegistry

/**
 * Resolves a presentation primitive's `asset:<path>` reference to the file
 * in the emitting skill's `assets` directory. Which file that is, and
 * refusing a path that leaves the directory, is the engine's call
 * (`SkillRegistry::asset_path`). Built-in skills have no assets, so their
 * references resolve to null and the caller falls back per primitive
 * (sound → system.notification, icon → no icon).
 */
@Singleton
class AssetResolver @Inject constructor(
    private val skillRegistry: SkillRegistry,
) {
    fun resolve(skillId: String, reference: String?): File? =
        reference?.let { skillRegistry.assetPath(skillId, it) }?.let(::File)

    fun uri(skillId: String, reference: String?): Uri? =
        resolve(skillId, reference)?.let { Uri.fromFile(it) }
}
