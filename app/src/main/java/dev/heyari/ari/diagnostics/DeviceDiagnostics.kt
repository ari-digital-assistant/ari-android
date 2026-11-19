package dev.heyari.ari.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.BuildConfig
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.llm.LlmDownloadManager
import dev.heyari.ari.llm.LlmModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import uniffi.ari_ffi.engineVersion
import uniffi.ari_ffi.onDeviceCpuInfo
import uniffi.ari_ffi.onDeviceRuntime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the device is and what Ari is running on it: the facts the debug
 * screen shows, a bug report carries, and the top of a report's log repeats.
 * Ids rather than display names, so a report reads the same in any language.
 */
data class DeviceDiagnostics(
    val device: String,
    /** The system-on-chip, e.g. "Google Tensor G5". Null before Android 12. */
    val chip: String?,
    val android: String,
    val memoryTotalMb: Long?,
    val cpuCores: Int,
    val abi: String,
    /**
     * Which build of llama.cpp's CPU code the on-device assistant runs:
     * "ARMv8.6", "ARMv8.2" or "ARMv8.0" (ari-android#32). Null when none
     * would load.
     */
    val cpuBuild: String?,
    val cpuBuildDescription: String?,
    /** The CPU features this device reports that llama.cpp can use. */
    val cpuFeatures: List<String>,
    val appVersion: String,
    val engineVersion: String?,
    val assistant: String?,
    val llmModel: String?,
    /**
     * What runs [llmModel]'s file: "LiteRT-LM" or "llama.cpp", which a tier
     * still on the GGUF it had before ari-android#52 uses. Null with no
     * model downloaded.
     */
    val llmRuntime: String?,
    val speech: String,
) {
    /** "gemma4-e2b-q4 (LiteRT-LM)", or the id alone with no runtime. */
    val llmSummary: String?
        get() = llmModel?.let { model -> llmRuntime?.let { "$model ($it)" } ?: model }

    /** "ARMv8.6 (dot product, …)", or null when no CPU build loaded. */
    val cpuBuildSummary: String?
        get() = cpuBuild?.let { build -> cpuBuildDescription?.let { "$build ($it)" } ?: build }

    /**
     * The same facts as plain text: for the top of a bug report's log, and
     * for the debug screen's copy button.
     */
    fun toText(): String = buildString {
        appendLine("Device: $device" + (chip?.let { " · $it" } ?: ""))
        appendLine("Android $android · ${memoryTotalMb?.let { "$it MB RAM" } ?: "RAM unknown"} · $cpuCores cores · $abi")
        appendLine("Assistant CPU build: ${cpuBuildSummary ?: "none loaded"}")
        appendLine("CPU features: ${cpuFeatures.joinToString().ifEmpty { "none reported" }}")
        appendLine("Ari $appVersion · engine ${engineVersion ?: "unknown"}")
        appendLine("Assistant: ${assistant ?: "none"} · model: ${llmSummary ?: "none"} · speech: $speech")
    }
}

@Singleton
class DiagnosticsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val llmDownloads: LlmDownloadManager,
) {
    /**
     * Off the main thread: asking which CPU build the assistant runs loads
     * it if no model has yet, a few MB read once per process.
     */
    suspend fun collect(): DeviceDiagnostics = withContext(Dispatchers.IO) {
        val memory = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it)
        }
        val cpu = runCatching { onDeviceCpuInfo() }.getOrNull()
        val sttMode = settings.sttMode.first()
        val llmModel = settings.activeLlmModelId.first()
        val llmFile = LlmModelRegistry.byId(llmModel)?.let { llmDownloads.modelFile(it) }?.takeIf { it.isFile }
        DeviceDiagnostics(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            chip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim().takeIf { it.isNotEmpty() }
            } else {
                null
            },
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            memoryTotalMb = (memory.totalMem / 1_000_000).takeIf { memory.totalMem > 0 },
            cpuCores = Runtime.getRuntime().availableProcessors(),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            cpuBuild = cpu?.build,
            cpuBuildDescription = cpu?.buildDescription,
            cpuFeatures = cpu?.features.orEmpty(),
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})",
            engineVersion = runCatching { engineVersion() }.getOrNull(),
            assistant = settings.activeAssistantId.first(),
            llmModel = llmModel,
            llmRuntime = llmFile?.let { runCatching { onDeviceRuntime(it.absolutePath) }.getOrNull() },
            speech = settings.activeSttModelId.first()?.let { "${sttMode.slug}, $it" } ?: sttMode.slug,
        )
    }
}
