package dev.heyari.ari.di

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.actions.AriFfiEnvelopeSink
import dev.heyari.ari.calendar.AriFfiCalendarProvider
import dev.heyari.ari.media.AriFfiMediaServicesProvider
import dev.heyari.ari.clock.AriFfiLocalClock
import dev.heyari.ari.data.SecretStore
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.llm.LlmDownloadManager
import dev.heyari.ari.llm.LlmModelRegistry
import dev.heyari.ari.locale.AriFfiLocaleProvider
import dev.heyari.ari.location.AriFfiLocationProvider
import dev.heyari.ari.skills.AndroidSkillLogSink
import dev.heyari.ari.tasks.AriFfiTasksProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uniffi.ari_ffi.AriEngine
import uniffi.ari_ffi.AriEngineBuilder
import uniffi.ari_ffi.AssistantRegistry
import uniffi.ari_ffi.SkillSettingsStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the one [AriEngine] instance and builds it off the main thread.
 *
 * The engine's construction is expensive — it compiles every installed WASM
 * skill (Cranelift), hydrates settings from DataStore, and applies assistant
 * state. Done eagerly on the main thread (which is what a plain
 * `@Provides @Singleton AriEngine` did, via Hilt field injection in
 * `AriApplication`), that work blocked `Application.onCreate` long enough to
 * trip a startup ANR once enough large skills were installed.
 *
 * So construction is no longer in the Hilt object graph as a direct binding.
 * Everything that needs the engine injects this holder and `await`s
 * [engine] from its own coroutine; the build runs exactly once on
 * [Dispatchers.Default] and every caller — including the main thread —
 * suspends (never blocks) until it's ready. [warmUp] kicks the build at app
 * start so the engine is usually ready before the first interaction; the
 * engine cwasm cache (see the Rust `compile_cache_dir`) keeps that build fast
 * after the first launch.
 */
@Singleton
class EngineHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
    private val llmDownloadManager: LlmDownloadManager,
    private val assistantRegistry: AssistantRegistry,
    private val skillSettingsStore: SkillSettingsStore,
    private val ariFfiTasksProvider: AriFfiTasksProvider,
    private val ariFfiCalendarProvider: AriFfiCalendarProvider,
    private val ariFfiLocationProvider: AriFfiLocationProvider,
    private val ariFfiLocalClock: AriFfiLocalClock,
    private val ariFfiEnvelopeSink: AriFfiEnvelopeSink,
    private val ariFfiLocaleProvider: AriFfiLocaleProvider,
    private val ariFfiSettingWriter: dev.heyari.ari.settings.AriFfiSettingWriter,
    private val ariFfiAuthorizeProvider: dev.heyari.ari.oauth.AriFfiAuthorizeProvider,
    private val ariFfiMediaServicesProvider: AriFfiMediaServicesProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var built: AriEngine? = null

    private val deferred: Deferred<AriEngine> =
        scope.async(start = CoroutineStart.LAZY) { build().also { built = it } }

    /**
     * Suspends until the engine is constructed, then returns it. Safe to call
     * from any dispatcher — the build runs once on [Dispatchers.Default] and
     * later callers get the cached instance. Callers on the main thread
     * suspend (not block) while the first build is in flight.
     */
    suspend fun engine(): AriEngine = deferred.await()

    /** Start the background build without waiting. Call once at app start. */
    fun warmUp() {
        scope.launch { deferred.await() }
    }

    /**
     * The engine if it's already built, else null — for best-effort callers
     * that must not suspend (e.g. `onTrimMemory`). Returns null before the
     * first build completes, in which case there's nothing to act on yet.
     */
    fun peek(): AriEngine? = built

    private suspend fun build(): AriEngine {
        // Build via the per-provider builder rather than one many-arg
        // constructor: passing all 11 providers in a single FFI call marshals
        // 11 by-value structs, which JNA mis-handles on arm64 (startup SIGSEGV
        // on real devices; benign on the x86_64 emulator). Each setter is one
        // call with a single arg, so nothing spills to the stack.
        val engine = AriEngineBuilder().use { b ->
            b.sink(AndroidSkillLogSink())
            b.tasks(ariFfiTasksProvider)
            b.calendar(ariFfiCalendarProvider)
            b.location(ariFfiLocationProvider)
            b.clock(ariFfiLocalClock)
            b.settings(skillSettingsStore)
            b.envelopeSink(ariFfiEnvelopeSink)
            b.locale(ariFfiLocaleProvider)
            b.settingWriter(ariFfiSettingWriter)
            b.authorize(ariFfiAuthorizeProvider)
            b.mediaServices(ariFfiMediaServicesProvider)
            b.build()
        }

        // Rehydrate non-secret skill settings from DataStore into the
        // in-memory FFI store BEFORE any skill runs. The store is
        // intentionally amnesiac across process restarts; without this
        // loop, a skill reading its own settings via `ari::setting_get`
        // sees null for every key until the user manually visits the
        // skill's settings page. Secrets live in EncryptedSharedPreferences
        // and are hydrated per-assistant below.
        val entries = settingsRepository.allAssistantConfigEntries()
        for (entry in entries) {
            skillSettingsStore.setValue(entry.skillId, entry.key, entry.value)
        }
        Log.i(TAG, "hydrated ${entries.size} non-secret skill setting(s) from DataStore")

        val skillsDir = File(context.filesDir, "skills").apply { mkdirs() }
        val storageDir = File(context.filesDir, "skill-storage").apply { mkdirs() }
        val loaded = engine.reloadCommunitySkills(
            skillsDir.absolutePath,
            storageDir.absolutePath,
        )
        Log.i(TAG, "loaded $loaded community skill(s) at startup")

        // Migrate from old activeLlmModelId to new activeAssistantId. If the
        // user had an LLM model selected but no assistant chosen, activate
        // the built-in assistant so they don't lose functionality.
        var activeAssistantId = settingsRepository.activeAssistantId.first()
        if (activeAssistantId == null) {
            val oldLlmId = settingsRepository.activeLlmModelId.first()
            if (oldLlmId != null) {
                activeAssistantId = EngineModule.BUILTIN_ASSISTANT_ID
                settingsRepository.setActiveAssistantId(EngineModule.BUILTIN_ASSISTANT_ID)
                Log.i(TAG, "migrated activeLlmModelId=$oldLlmId to assistant=${EngineModule.BUILTIN_ASSISTANT_ID}")
            }
        }
        if (activeAssistantId != null) {
            assistantRegistry.setActiveAssistant(activeAssistantId)

            // If the active assistant is the built-in local LLM, set the
            // model path so it loads lazily on first skill miss.
            if (activeAssistantId == EngineModule.BUILTIN_ASSISTANT_ID) {
                var modelId = settingsRepository.activeLlmModelId.first()
                // Recovery: an onboarding user can finish a tier download
                // before SettingsViewModel ever subscribes to its
                // completion event, in which case `activeLlmModelId` never
                // gets written. If we see Builtin active but no model id,
                // scan the LLM directory and pick the first downloaded one —
                // the user's intent was to use it.
                if (modelId == null) {
                    val recovered = LlmModelRegistry.all
                        .firstOrNull { llmDownloadManager.isDownloaded(it) }
                    if (recovered != null) {
                        settingsRepository.setActiveLlmModelId(recovered.id)
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
                    // to no active assistant and Layer C would silently fall
                    // through to warn-and-commit.
                    val tier = model.size.name.lowercase()
                    assistantRegistry.setAssistantConfigValue(
                        EngineModule.BUILTIN_ASSISTANT_ID,
                        "model_tier",
                        tier,
                    )
                    settingsRepository.setAssistantConfigValue(
                        EngineModule.BUILTIN_ASSISTANT_ID,
                        "model_tier",
                        tier,
                    )
                }
            }

            Log.i(TAG, "active assistant: $activeAssistantId")
        }

        // Restore encrypted secrets for ALL installed assistants (not just
        // the active one) — named-assistant routing dispatches to any
        // installed cloud assistant by alias regardless of which is active,
        // and that needs API keys in the FFI config store.
        for ((ids, value) in secretStore.allEntries()) {
            assistantRegistry.setAssistantConfigValue(ids.first, ids.second, value)
        }

        // Always apply to the engine. When no assistant is active this sets
        // active=None, but it's still required because the call also pushes
        // the named-assistant binding list — without it, "ask <alias> X"
        // routing wouldn't see installed assistants until the user next
        // touched assistant Settings.
        assistantRegistry.applyToEngine(engine)

        // Load the FunctionGemma router if enabled and downloaded.
        val routerEnabled = settingsRepository.routerEnabled.first()
        if (routerEnabled) {
            val routerFile = File(context.filesDir, "models/router/${EngineModule.ROUTER_MODEL_FILENAME}")
            if (routerFile.isFile) {
                val ok = engine.loadRouterModel(routerFile.absolutePath)
                Log.i(TAG, if (ok) "Router loaded (lazy)" else "Router path invalid")
            }
        }

        return engine
    }

    private companion object {
        const val TAG = "EngineHolder"
    }
}
