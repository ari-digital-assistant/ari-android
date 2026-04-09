package dev.heyari.ari.di

import android.app.Application
import android.content.Context
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.heyari.ari.actions.ActionHandler
import dev.heyari.ari.actions.AppLauncher
import dev.heyari.ari.actions.WebSearchLauncher
import dev.heyari.ari.audio.CaptureBus
import dev.heyari.ari.stt.ModelDownloadManager
import dev.heyari.ari.stt.SpeechRecognizer
import dev.heyari.ari.tts.SpeechOutput
import uniffi.ari_ffi.AriEngine
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    @Singleton
    fun provideAriEngine(@ApplicationContext context: Context): AriEngine {
        val engine = AriEngine()
        // Load every installed community skill into the conversation
        // engine at startup. Both paths must match what SkillRegistryModule
        // uses — otherwise a skill's installed state (filesystem location)
        // or its storage_kv JSON would be invisible at conversation time.
        val skillsDir = File(context.filesDir, "skills").apply { mkdirs() }
        val storageDir = File(context.filesDir, "skill-storage").apply { mkdirs() }
        val loaded = engine.reloadCommunitySkills(
            skillsDir.absolutePath,
            storageDir.absolutePath,
        )
        Log.i("EngineModule", "loaded $loaded community skill(s) at startup")
        return engine
    }

    @Provides
    @Singleton
    fun provideSpeechRecognizer(captureBus: CaptureBus): SpeechRecognizer =
        SpeechRecognizer(captureBus)

    @Provides
    @Singleton
    fun provideSpeechOutput(application: Application): SpeechOutput =
        SpeechOutput(application)

    @Provides
    @Singleton
    fun provideModelDownloadManager(@ApplicationContext context: Context): ModelDownloadManager =
        ModelDownloadManager(context)

    @Provides
    @Singleton
    fun provideAppLauncher(@ApplicationContext context: Context): AppLauncher =
        AppLauncher(context)

    @Provides
    @Singleton
    fun provideWebSearchLauncher(@ApplicationContext context: Context): WebSearchLauncher =
        WebSearchLauncher(context)

    @Provides
    @Singleton
    fun provideActionHandler(
        appLauncher: AppLauncher,
        webSearchLauncher: WebSearchLauncher,
    ): ActionHandler = ActionHandler(appLauncher, webSearchLauncher)
}
