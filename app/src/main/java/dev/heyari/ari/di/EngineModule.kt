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
import dev.heyari.ari.actions.AriFfiEnvelopeSink
import dev.heyari.ari.actions.WebSearchLauncher
import dev.heyari.ari.audio.CaptureBus
import dev.heyari.ari.calendar.AriFfiCalendarProvider
import dev.heyari.ari.clock.AriFfiLocalClock
import dev.heyari.ari.data.SecretStore
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.llm.LlmDownloadManager
import dev.heyari.ari.llm.LlmModelRegistry
import dev.heyari.ari.locale.AriFfiLocaleProvider
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
        ariFfiEnvelopeSink: AriFfiEnvelopeSink,
        ariFfiLocaleProvider: AriFfiLocaleProvider,
    ): AriEngine {
        // Hand the engine a full set of platform providers. Any skill
        // declaring `Capability::Tasks` / `Capability::Calendar`, or
        // reading `ari::local_now_components()`, ends up routed to
        // these Android-specific implementations. The skill code is
        // platform-agnostic; everything Android-shaped lives here.
        //
        // `envelopeSink` is the async push channel the engine uses to
        // deliver Layer C phase-2 envelopes (after the assistant
        // round-trip) back to the viewmodel. Without it, the engine
        // falls back to pre-Layer-C behaviour: skill's phase-1
        // envelope is returned unchanged and the round-trip is
        // suppressed.
        val engine = AriEngine.withPlatformProviders(
            sink = AndroidSkillLogSink(),
            tasks = ariFfiTasksProvider,
            calendar = ariFfiCalendarProvider,
            clock = ariFfiLocalClock,
            // Threading the shared settings store through so the
            // engine's `ari::setting_get` WASM import reads live
            // values the Android settings UI wrote. The same
            // `skillSettingsStore` is injected into AssistantRegistry
            // and SkillRegistry — single source of truth.
            settings = skillSettingsStore,
            envelopeSink = ariFfiEnvelopeSink,
            // The user's chosen language. Engine reads through this
            // whenever it needs to dispatch on locale (text
            // normalisers, prompt selection, skill regex filtering).
            // Single source of truth lives in SettingsRepository.
            locale = ariFfiLocaleProvider,
        )

        // Rehydrate non-secret skill settings from DataStore into the
        // in-memory FFI store BEFORE any skill runs. The store is
        // intentionally amnesiac across process restarts; without this
        // loop, a skill reading its own settings via
        // `ari::setting_get` sees null for every key until the user
        // manually visits the skill's settings page and
        // SkillsViewModel.loadSkillSettings does per-screen hydration.
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
                var modelId = runBlocking { settingsRepository.activeLlmModelId.first() }
                // Recovery: an onboarding user can finish a tier download
                // before SettingsViewModel ever subscribes to its
                // completion event, in which case `activeLlmModelId`
                // never gets written. If we see Builtin active but no
                // model id, scan the LLM directory and pick the first
                // downloaded one — the user's intent was to use it.
                if (modelId == null) {
                    val recovered = LlmModelRegistry.all
                        .firstOrNull { llmDownloadManager.isDownloaded(it) }
                    if (recovered != null) {
                        runBlocking { settingsRepository.setActiveLlmModelId(recovered.id) }
                        modelId = recovered.id
                        Log.i(TAG, "recovered activeLlmModelId from disk scan: $modelId")
                    }
                }
                val model = LlmModelRegistry.byId(modelId)
                if (model != null && llmDownloadManager.isDownloaded(model)) {
                    val ok = engine.loadLlmModel(llmDownloadManager.modelFile(model).absolutePath)
                    Log.i(TAG, if (ok) "LLM path set: ${model.id} (lazy)" else "LLM path invalid: ${model.id}")
                    // Derive model_tier from the LlmModel.size and ensure it's
                    // written to both the FFI store and DataStore. Existing
                    // installs (pre-Layer-C-on-device) had no model_tier
                    // persisted; without this, apply_to_engine would default
                    // to no active assistant and Layer C would silently
                    // fall through to warn-and-commit.
                    val tier = model.size.name.lowercase()
                    runBlocking {
                        assistantRegistry.setAssistantConfigValue(
                            BUILTIN_ASSISTANT_ID,
                            "model_tier",
                            tier,
                        )
                        settingsRepository.setAssistantConfigValue(
                            BUILTIN_ASSISTANT_ID,
                            "model_tier",
                            tier,
                        )
                    }
                }
            }

            Log.i(TAG, "active assistant: $activeAssistantId")
        }

        // Restore encrypted secrets for ALL installed assistants (not
        // just the active one) — named-assistant routing dispatches to
        // any installed cloud assistant by alias regardless of which is
        // active, and that needs API keys in the FFI config store.
        for ((ids, value) in secretStore.allEntries()) {
            assistantRegistry.setAssistantConfigValue(ids.first, ids.second, value)
        }

        // Always apply to the engine. When no assistant is active this
        // sets active=None, but it's still required because the call
        // also pushes the named-assistant binding list — without it,
        // "ask <alias> X" routing wouldn't see installed assistants
        // until the user next touched assistant Settings.
        assistantRegistry.applyToEngine(engine)

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
    /**
     * Manifest URL for the FunctionGemma router. CI publishes a fresh
     * manifest to the `functiongemma-latest` release on every nightly
     * retrain; auto-update polls this URL on its 24h cadence.
     */
    const val ROUTER_MODEL_MANIFEST_URL = "https://github.com/ari-digital-assistant/ari-tools/releases/download/functiongemma-latest/manifest.json"
    /** Stable identifier for the router across DataStore keys + sidecars. */
    const val ROUTER_MODEL_KEY = "router"
    private const val TAG = "EngineModule"

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
    fun provideActionHandler(
        @ApplicationContext context: Context,
        appLauncher: AppLauncher,
        webSearchLauncher: WebSearchLauncher,
        presentationCoordinator: dev.heyari.ari.actions.PresentationCoordinator,
    ): ActionHandler = ActionHandler(
        context,
        appLauncher,
        webSearchLauncher,
        presentationCoordinator,
    )
}
