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
import dev.heyari.ari.calendar.AriFfiCalendarProvider
import dev.heyari.ari.clock.AriFfiLocalClock
import dev.heyari.ari.data.SecretStore
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.llm.LlmDownloadManager
import dev.heyari.ari.llm.LlmModelRegistry
import dev.heyari.ari.router.RouterDownloadManager
import dev.heyari.ari.skills.AndroidSkillLogSink
import dev.heyari.ari.stt.ModelDownloadManager
import dev.heyari.ari.tasks.AriFfiTasksProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import dev.heyari.ari.stt.SpeechRecognizer
import dev.heyari.ari.tts.SpeechOutput
import uniffi.ari_ffi.AriEngine
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

    @Provides
    @Singleton
    fun provideAriEngine(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
        secretStore: SecretStore,
        llmDownloadManager: LlmDownloadManager,
        assistantRegistry: AssistantRegistry,
        skillSettingsStore: SkillSettingsStore,
        ariFfiTasksProvider: AriFfiTasksProvider,
        ariFfiCalendarProvider: AriFfiCalendarProvider,
        ariFfiLocalClock: AriFfiLocalClock,
    ): AriEngine {
        // Hand the engine a full set of platform providers. Any skill
        // declaring `Capability::Tasks` / `Capability::Calendar`, or
        // reading `ari::local_now_components()`, ends up routed to
        // these Android-specific implementations. The skill code is
        // platform-agnostic; everything Android-shaped lives here.
        val engine = AriEngine.withPlatformProviders(
            sink = AndroidSkillLogSink(),
            tasks = ariFfiTasksProvider,
            calendar = ariFfiCalendarProvider,
            clock = ariFfiLocalClock,
        )

        // Rehydrate non-secret skill settings from DataStore into the
        // in-memory FFI store BEFORE any skill runs. The store is
        // intentionally amnesiac across process restarts; without this
        // loop, skill code (e.g. the reminder handler reading its
        // `default_task_list`) sees null for every setting until the
        // user manually visits that skill's settings page and
        // SkillsViewModel.loadSkillSettings does per-screen hydration.
        // That was the cause of reminders silently landing on
        // `available.first()` instead of the user's selected default.
        // Secrets live in EncryptedSharedPreferences and are still
        // hydrated per-active-assistant below.
        val nonSecretHydrated = runBlocking {
            val entries = settingsRepository.allAssistantConfigEntries()
            for (entry in entries) {
                skillSettingsStore.setValue(entry.skillId, entry.key, entry.value)
            }
            entries.size
        }
        Log.i(TAG, "hydrated $nonSecretHydrated non-secret skill setting(s) from DataStore")

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

            // Restore encrypted secrets into the in-memory FFI config store
            // so the engine can use API keys etc. at runtime.
            for ((ids, value) in secretStore.allEntries()) {
                assistantRegistry.setAssistantConfigValue(ids.first, ids.second, value)
            }

            assistantRegistry.applyToEngine(engine)
            Log.i(TAG, "active assistant: $activeAssistantId")
        }

        // Load the FunctionGemma router if enabled and downloaded.
        val routerEnabled = runBlocking { settingsRepository.routerEnabled.first() }
        if (routerEnabled) {
            val routerFile = File(context.filesDir, "models/router/$ROUTER_MODEL_FILENAME")
            if (routerFile.isFile) {
                val ok = engine.loadRouterModel(routerFile.absolutePath)
                Log.i(TAG, if (ok) "Router loaded (lazy)" else "Router path invalid")
            }
        }

        return engine
    }

    const val BUILTIN_ASSISTANT_ID = "dev.heyari.assistant.local"
    const val ROUTER_MODEL_FILENAME = "ari-functiongemma-q4_k_m.gguf"
    const val ROUTER_MODEL_URL = "https://github.com/ari-digital-assistant/ari-tools/releases/download/functiongemma-v1/ari-functiongemma-q4_k_m.gguf"
    const val ROUTER_MODEL_BYTES = 253_000_000L
    private const val TAG = "EngineModule"

    @Provides
    @Singleton
    fun provideSpeechRecognizer(captureBus: CaptureBus): SpeechRecognizer =
        SpeechRecognizer(captureBus)

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
    fun provideActionHandler(
        @ApplicationContext context: Context,
        appLauncher: AppLauncher,
        webSearchLauncher: WebSearchLauncher,
        presentationCoordinator: dev.heyari.ari.actions.PresentationCoordinator,
        reminderActionHandler: dev.heyari.ari.reminders.ReminderActionHandler,
    ): ActionHandler = ActionHandler(
        context,
        appLauncher,
        webSearchLauncher,
        presentationCoordinator,
        reminderActionHandler,
    )
}
