package dev.heyari.ari.di

import android.app.Application
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.heyari.ari.actions.ActionHandler
import dev.heyari.ari.actions.AppLauncher
import dev.heyari.ari.actions.WebSearchLauncher
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.llm.LlmDownloadManager
import dev.heyari.ari.router.RouterDownloadManager
import dev.heyari.ari.stt.ModelDownloadManager
import dev.heyari.ari.tts.SpeechOutput
import uniffi.ari_ffi.AssistantRegistry
import uniffi.ari_ffi.SkillSettingsStore
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    @Singleton
    fun provideAssistantRegistry(
        @ApplicationContext context: Context,
        settingsStore: SkillSettingsStore,
    ): AssistantRegistry {
        val skillsDir = File(context.filesDir, "skills").apply { mkdirs() }
        val storageDir = File(context.filesDir, "skill-storage").apply { mkdirs() }
        return AssistantRegistry(
            skillsDir.absolutePath,
            storageDir.absolutePath,
            settingsStore,
        )
    }

    // The AriEngine is no longer a direct Hilt binding — it's built off the
    // main thread by [EngineHolder]. Constructing it eagerly here (Hilt
    // resolved it on the main thread the moment anything injected it) was the
    // root of the startup ANR. Consumers inject EngineHolder and `await` the
    // engine from their own coroutine instead.

    const val BUILTIN_ASSISTANT_ID = "dev.heyari.assistant.local"
    const val ROUTER_MODEL_FILENAME = "ari-functiongemma-q4_k_m.gguf"
    const val ROUTER_MODEL_URL = "https://github.com/ari-digital-assistant/ari-tools/releases/download/functiongemma-v1/ari-functiongemma-q4_k_m.gguf"
    const val ROUTER_MODEL_BYTES = 253_000_000L
    /**
     * Manifest URL for the FunctionGemma router. CI publishes a fresh
     * manifest to the `functiongemma-latest` release on every nightly
     * retrain; auto-update polls this URL on its 24h cadence.
     */
    const val ROUTER_MODEL_MANIFEST_URL = "https://github.com/ari-digital-assistant/ari-tools/releases/download/functiongemma-latest/manifest.json"
    /** Stable identifier for the router across DataStore keys + sidecars. */
    const val ROUTER_MODEL_KEY = "router"

    // SpeechRecognizer is constructed by Hilt via its own @Inject constructor —
    // no @Provides needed. It depends on CaptureBus + AriFfiLocaleProvider,
    // both of which Hilt resolves automatically.

    @Provides
    @Singleton
    fun provideSpeechOutput(
        application: Application,
        settingsRepository: SettingsRepository,
    ): SpeechOutput = SpeechOutput(application, settingsRepository)

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
    fun provideRouterDownloadManager(@ApplicationContext context: Context): RouterDownloadManager =
        RouterDownloadManager(context)

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
    fun provideMusicLauncher(@ApplicationContext context: Context): dev.heyari.ari.actions.MusicLauncher =
        dev.heyari.ari.actions.MusicLauncher(context)

    @Provides
    @Singleton
    fun provideActionHandler(
        @ApplicationContext context: Context,
        appLauncher: AppLauncher,
        webSearchLauncher: WebSearchLauncher,
        musicLauncher: dev.heyari.ari.actions.MusicLauncher,
        alarmLauncher: dev.heyari.ari.actions.AlarmLauncher,
        mediaTransportController: dev.heyari.ari.actions.MediaTransportController,
        presentationCoordinator: dev.heyari.ari.actions.PresentationCoordinator,
    ): ActionHandler = ActionHandler(
        context,
        appLauncher,
        webSearchLauncher,
        musicLauncher,
        alarmLauncher,
        mediaTransportController,
        presentationCoordinator,
    )
}
