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
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.llm.LlmDownloadManager
import dev.heyari.ari.llm.LlmModelRegistry
import dev.heyari.ari.stt.ModelDownloadManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import dev.heyari.ari.stt.SpeechRecognizer
import dev.heyari.ari.tts.SpeechOutput
import uniffi.ari_ffi.AriEngine
import uniffi.ari_ffi.AssistantRegistry
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    @Singleton
    fun provideAssistantRegistry(
        @ApplicationContext context: Context,
    ): AssistantRegistry {
        val skillsDir = File(context.filesDir, "skills").apply { mkdirs() }
        val storageDir = File(context.filesDir, "skill-storage").apply { mkdirs() }
        return AssistantRegistry(
            skillsDir.absolutePath,
            storageDir.absolutePath,
        )
    }

    @Provides
    @Singleton
    fun provideAriEngine(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
        llmDownloadManager: LlmDownloadManager,
        assistantRegistry: AssistantRegistry,
    ): AriEngine {
        val engine = AriEngine()
        val skillsDir = File(context.filesDir, "skills").apply { mkdirs() }
        val storageDir = File(context.filesDir, "skill-storage").apply { mkdirs() }
        val loaded = engine.reloadCommunitySkills(
            skillsDir.absolutePath,
            storageDir.absolutePath,
        )
        Log.i(TAG, "loaded $loaded community skill(s) at startup")

        // Migrate from old activeLlmModelId to new activeAssistantId.
        // If the user had an LLM model selected but no assistant chosen,
        // activate the built-in assistant so they don't lose functionality.
        var activeAssistantId = runBlocking { settingsRepository.activeAssistantId.first() }
        if (activeAssistantId == null) {
            val oldLlmId = runBlocking { settingsRepository.activeLlmModelId.first() }
            if (oldLlmId != null) {
                activeAssistantId = BUILTIN_ASSISTANT_ID
                runBlocking { settingsRepository.setActiveAssistantId(BUILTIN_ASSISTANT_ID) }
                Log.i(TAG, "migrated activeLlmModelId=$oldLlmId to assistant=$BUILTIN_ASSISTANT_ID")
            }
        }
        if (activeAssistantId != null) {
            assistantRegistry.setActiveAssistant(activeAssistantId)

            // If the active assistant is the built-in local LLM, set the
            // model path so it loads lazily on first skill miss.
            if (activeAssistantId == BUILTIN_ASSISTANT_ID) {
                val modelId = runBlocking { settingsRepository.activeLlmModelId.first() }
                val model = LlmModelRegistry.byId(modelId)
                if (model != null && llmDownloadManager.isDownloaded(model)) {
                    val ok = engine.loadLlmModel(llmDownloadManager.modelFile(model).absolutePath)
                    Log.i(TAG, if (ok) "LLM path set: ${model.id} (lazy)" else "LLM path invalid: ${model.id}")
                }
            }

            assistantRegistry.applyToEngine(engine)
            Log.i(TAG, "active assistant: $activeAssistantId")
        }

        return engine
    }

    const val BUILTIN_ASSISTANT_ID = "dev.heyari.assistant.local"
    private const val TAG = "EngineModule"

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
    fun provideLlmDownloadManager(@ApplicationContext context: Context): LlmDownloadManager =
        LlmDownloadManager(context)

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
