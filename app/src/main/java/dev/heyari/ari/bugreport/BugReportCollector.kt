package dev.heyari.ari.bugreport

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.BuildConfig
import dev.heyari.ari.data.SecretStore
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.data.conversation.ConversationLogRepository
import dev.heyari.ari.stt.UtteranceCaptureStore
import dev.heyari.ari.wakeword.WakeCaptureStore
import uniffi.ari_ffi.engineVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BugReportCollector"

/** How much of the log to keep. Enough to hold the incident, not the week. */
private const val LOGCAT_LINES = 2000

/**
 * What a diagnostic file would cost to send, so the report screen can show a
 * real size next to a real name instead of asking people to consent to
 * "recordings".
 */
data class AttachmentOffer(
    val kind: AttachmentKind,
    val fileCount: Int,
    val bytes: Long,
    /** Ticked by default. False for anything carrying speech or conversation. */
    val defaultOn: Boolean,
)

/**
 * Gathers everything a bug report can carry.
 *
 * Reads from what the app already keeps — the capture stores, the settings,
 * the conversation log — and never starts recording anything of its own. The
 * logcat is filtered to Ari's own process and put through [LogScrubber] before
 * it is written anywhere, so the copy that reaches the staging directory is
 * already redacted.
 */
@Singleton
class BugReportCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val conversation: ConversationLogRepository,
    private val wakeCaptures: WakeCaptureStore,
    private val utteranceCaptures: UtteranceCaptureStore,
) {

    /**
     * The engine version comes from the FFI rather than from anything the app
     * hardcodes, so it can never drift from the library actually loaded — a
     * bug report that names the wrong engine is worse than one that names
     * none. Null only if the native library failed to load, at which point
     * the report has bigger news to carry.
     */
    suspend fun appInfo(): AppInfo = AppInfo(
        version = BuildConfig.VERSION_NAME,
        buildType = BuildConfig.BUILD_TYPE,
        engineVersion = runCatching { engineVersion() }.getOrNull(),
        locale = settings.activeLocale.first(),
    )

    suspend fun setupInfo(): SetupInfo = SetupInfo(
        assistant = settings.activeAssistantId.first(),
        model = settings.activeLlmModelId.first(),
        stt = settings.activeSttModelId.first() ?: settings.cloudSttModel.first().ifBlank { null },
        tts = settings.activeTtsVoice.first(),
        wake = settings.activeWakeWordId.first(),
    )

    /**
     * The device facts that decide half the bugs we will get.
     *
     * The fingerprint names the ROM, which is how a GrapheneOS or de-Googled
     * device announces itself without us having to sniff for one. The battery
     * exemption is the other half: most "the wake word just stopped" reports
     * are a doze policy rather than a defect.
     */
    fun deviceInfo(): DeviceInfo {
        val memory = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it)
        }
        val power = context.getSystemService(PowerManager::class.java)
        return DeviceInfo(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            fingerprint = Build.FINGERPRINT,
            ramFreeMb = (memory.availMem / (1024 * 1024)).toInt().takeIf { memory.availMem > 0 },
            storageFreeMb = (context.filesDir.usableSpace / (1024 * 1024)).toInt(),
            network = networkKind(),
            batteryExempt = power?.isIgnoringBatteryOptimizations(context.packageName),
            permissions = grantedPermissions(),
        )
    }

    private fun networkKind(): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    /**
     * Only the permissions that change how Ari behaves, and only whether they
     * were granted. The full manifest list is noise; these five are the ones
     * that turn a working feature into a silent one.
     */
    private fun grantedPermissions(): List<String> = INTERESTING_PERMISSIONS
        .filter {
            context.checkSelfPermission(it.second) == PackageManager.PERMISSION_GRANTED
        }
        .map { it.first }

    suspend fun installedSkills(): List<SkillInfo> = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "skills")
        val dirs = root.listFiles { f: File -> f.isDirectory }.orEmpty()
        dirs.mapNotNull { dir ->
            val manifest = File(dir, "SKILL.en.md").takeIf { it.exists() } ?: return@mapNotNull null
            val version = runCatching {
                manifest.useLines { lines ->
                    lines.take(40)
                        .firstOrNull { it.trimStart().startsWith("version:") }
                        ?.substringAfter(':')
                        ?.trim()
                        ?.trim('"')
                }
            }.getOrNull()
            SkillInfo(id = dir.name, version = version)
        }.sortedBy { it.id }
    }

    /**
     * What is actually available to attach, with real sizes.
     *
     * A kind with nothing behind it is left out entirely rather than offered
     * as an empty tick — there is no point asking somebody to consent to
     * sending nothing.
     */
    fun offers(hasScreenshot: Boolean): List<AttachmentOffer> = buildList {
        add(AttachmentOffer(AttachmentKind.LOGCAT, 1, 0, defaultOn = true))
        if (hasScreenshot) {
            add(AttachmentOffer(AttachmentKind.SCREENSHOT, 1, 0, defaultOn = true))
        }
        val turns = conversation.messages.value.size
        if (turns > 0) {
            add(AttachmentOffer(AttachmentKind.CONVERSATION, turns, 0, defaultOn = false))
        }
        utteranceCaptures.stats().takeIf { it.count > 0 }?.let {
            add(AttachmentOffer(AttachmentKind.COMMANDS, it.count, it.totalBytes, defaultOn = false))
        }
        wakeCaptures.stats().takeIf { it.count > 0 }?.let {
            add(AttachmentOffer(AttachmentKind.WAKE_AUDIO, it.count, it.totalBytes, defaultOn = false))
        }
    }

    /**
     * Writes the chosen kinds into [staging] and returns them.
     *
     * A kind that produces nothing is dropped rather than uploaded empty: the
     * byte count is signed into the upload URL, so a zero-length file would be
     * refused by storage and take the whole report down with it.
     */
    suspend fun stage(
        staging: File,
        kinds: Set<AttachmentKind>,
        screenshot: ByteArray?,
    ): List<BugAttachment> = withContext(Dispatchers.IO) {
        staging.listFiles()?.forEach { it.delete() }
        kinds.mapNotNull { kind ->
            val file = File(staging, "${kind.wireName}.${kind.extension}")
            val written = runCatching {
                when (kind) {
                    AttachmentKind.LOGCAT -> file.writeText(scrubbedLogcat())
                    AttachmentKind.SCREENSHOT -> screenshot?.let { file.writeBytes(it) }
                    AttachmentKind.CONVERSATION -> file.writeText(conversationJson())
                    AttachmentKind.COMMANDS -> zipInto(file, utteranceCaptures.files())
                    AttachmentKind.WAKE_AUDIO -> zipInto(file, wakeCaptures.files())
                    AttachmentKind.ALL_AUDIO ->
                        zipInto(file, utteranceCaptures.files() + wakeCaptures.files())
                }
            }.onFailure { Log.w(TAG, "could not stage ${kind.wireName}", it) }
            if (written.isSuccess && file.length() > 0) BugAttachment(kind, file) else null
        }
    }

    /**
     * Ari's own log, redacted.
     *
     * `logcat --pid` is the whole of the filtering: since Android 4.1 an app
     * can only read its own entries anyway, so this is a narrowing rather than
     * a privacy boundary. The scrubber is the privacy boundary, and it runs
     * before a single byte is written to disk.
     */
    private suspend fun scrubbedLogcat(): String {
        val raw = runCatching {
            val process = ProcessBuilder(
                "logcat", "-d", "-t", LOGCAT_LINES.toString(), "--pid=${android.os.Process.myPid()}",
            ).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
        }.getOrElse {
            Log.w(TAG, "could not read logcat", it)
            return "logcat was not readable on this device"
        }
        return LogScrubber(knownSecrets()).scrub(raw)
    }

    /**
     * Every value the app stored that would be a disaster in a log, named so a
     * redaction still says which credential was involved.
     */
    private suspend fun knownSecrets(): List<KnownSecret> = buildList {
        secrets.allEntries().forEach { (id, value) ->
            add(KnownSecret("${id.first}_${id.second}", value))
        }
        settings.cloudSttEndpoint.first().takeIf { it.isNotBlank() }
            ?.let { add(KnownSecret("cloud_stt_endpoint", it)) }
        settings.allAssistantConfigEntries().forEach { entry ->
            add(KnownSecret("${entry.skillId}_${entry.key}", entry.value))
        }
        settings.listeningPlacesOnce().forEach { place ->
            add(KnownSecret("listening_place", place.name))
        }
    }

    private fun conversationJson(): String = JSONArray().apply {
        conversation.messages.value.forEach { message ->
            put(JSONObject().apply {
                put("from", if (message.isFromUser) "user" else "ari")
                put("text", message.text)
            })
        }
    }.toString()

    private fun zipInto(target: File, sources: List<File>) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            sources.filter { it.isFile }.forEach { source ->
                zip.putNextEntry(ZipEntry(source.name))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private companion object {
        /** Display name to manifest permission, for the five that matter. */
        val INTERESTING_PERMISSIONS = listOf(
            "mic" to android.Manifest.permission.RECORD_AUDIO,
            "notifications" to android.Manifest.permission.POST_NOTIFICATIONS,
            "location" to android.Manifest.permission.ACCESS_FINE_LOCATION,
            "background-location" to android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            "contacts" to android.Manifest.permission.READ_CONTACTS,
        )
    }
}

private val AttachmentKind.extension: String
    get() = when (this) {
        AttachmentKind.LOGCAT -> "txt"
        AttachmentKind.SCREENSHOT -> "png"
        AttachmentKind.CONVERSATION -> "json"
        AttachmentKind.COMMANDS, AttachmentKind.WAKE_AUDIO, AttachmentKind.ALL_AUDIO -> "zip"
    }
