package dev.heyari.ari.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import uniffi.ari_ffi.SkillRegistry
import javax.inject.Singleton
import java.io.File

@Module
@InstallIn(SingletonComponent::class)
object SkillRegistryModule {

    /**
     * Opens the process-wide skill store under the app's private files
     * directory. The store owns `skills/` (one subdir per installed skill)
     * and the sibling `skill-storage/` dir holds per-skill `storage_kv` JSON
     * files.
     *
     * Construction is cheap: it creates the directories if missing and
     * scans whatever's there to build an in-memory index. No network, no
     * WASM instantiation — that happens later, on demand.
     */
    @Provides
    @Singleton
    fun provideSkillRegistry(@ApplicationContext context: Context): SkillRegistry {
        val root = File(context.filesDir, "skills").apply { mkdirs() }
        val storage = File(context.filesDir, "skill-storage").apply { mkdirs() }
        return SkillRegistry(root.absolutePath, storage.absolutePath)
    }
}
