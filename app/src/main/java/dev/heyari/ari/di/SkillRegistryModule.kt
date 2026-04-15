package dev.heyari.ari.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import uniffi.ari_ffi.SkillRegistry
import uniffi.ari_ffi.SkillSettingsStore
import javax.inject.Singleton
import java.io.File

@Module
@InstallIn(SingletonComponent::class)
object SkillRegistryModule {

    /**
     * Process-wide in-memory mirror of every per-skill setting value.
     * One instance, shared with [uniffi.ari_ffi.AssistantRegistry] (see
     * [EngineModule.provideAssistantRegistry]) so writes from either
     * the Skills detail page or the Assistants page land in the same
     * map and the engine's outbound API call path sees them all.
     *
     * Hydrated at startup from DataStore + EncryptedSharedPreferences
     * by EngineModule; this provider just constructs the empty box.
     */
    @Provides
    @Singleton
    fun provideSkillSettingsStore(): SkillSettingsStore = SkillSettingsStore()

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
    fun provideSkillRegistry(
        @ApplicationContext context: Context,
        settingsStore: SkillSettingsStore,
    ): SkillRegistry {
        val root = File(context.filesDir, "skills").apply { mkdirs() }
        val storage = File(context.filesDir, "skill-storage").apply { mkdirs() }
        return SkillRegistry(root.absolutePath, storage.absolutePath, settingsStore)
    }
}
