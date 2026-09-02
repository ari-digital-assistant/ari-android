package dev.heyari.ari.bugreport

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A diagnostic file the reporter agreed to send. The wire name is what the
 * server validates against; anything else is refused before a URL is signed.
 */
enum class AttachmentKind(val wireName: String, val contentType: String) {
    LOGCAT("logcat", "text/plain"),
    SCREENSHOT("screenshot", "image/png"),
    CONVERSATION("conversation", "application/json"),
    COMMANDS("commands", "application/zip"),
    WAKE_AUDIO("wake-audio", "application/zip"),
    ALL_AUDIO("all-audio", "application/zip"),
}

/**
 * Staged on disk rather than held in memory: the audio bundles run to
 * megabytes, and the upload streams straight from the file to S3.
 */
data class BugAttachment(val kind: AttachmentKind, val file: File) {
    val bytes: Long get() = file.length()
}

data class AppInfo(
    val version: String,
    val buildType: String,
    val commit: String? = null,
    val engineVersion: String? = null,
    val locale: String? = null,
)

data class SetupInfo(
    val assistant: String? = null,
    val model: String? = null,
    val stt: String? = null,
    val tts: String? = null,
    val wake: String? = null,
)

data class DeviceInfo(
    val model: String,
    val androidVersion: String,
    val fingerprint: String? = null,
    val ramFreeMb: Int? = null,
    val storageFreeMb: Int? = null,
    val network: String? = null,
    val batteryExempt: Boolean? = null,
    val permissions: List<String> = emptyList(),
)

data class SkillInfo(val id: String, val version: String?)

/**
 * Everything the reporter agreed to send, as the review screen showed it.
 *
 * [description] is published verbatim on a public GitHub issue and the field
 * that collects it says so. [privateNote] never leaves the private bucket,
 * which is the whole reason it exists: free text cannot be scrubbed, so the
 * only honest answer is to give people somewhere else to put it.
 */
data class BugReport(
    val installId: String,
    val description: String,
    val privateNote: String? = null,
    val stackTrace: String? = null,
    val app: AppInfo,
    val setup: SetupInfo = SetupInfo(),
    val device: DeviceInfo,
    val skills: List<SkillInfo> = emptyList(),
    val attachments: List<BugAttachment> = emptyList(),
)

private fun JSONObject.putIfPresent(name: String, value: Any?) {
    if (value != null) put(name, value)
}

/**
 * The body of `POST /api/bug`.
 *
 * Absent fields are omitted rather than sent as empty strings or zeroes, so
 * "not recorded" reads as absent on the issue instead of as a measurement of
 * nothing. Only the byte count of each attachment goes here — the files
 * themselves go straight to S3 on the pre-signed URLs that come back.
 */
fun BugReport.toWireJson(): JSONObject = JSONObject().apply {
    put("installId", installId)
    put("description", description)
    putIfPresent("privateNote", privateNote?.takeIf { it.isNotBlank() })
    putIfPresent("stackTrace", stackTrace?.takeIf { it.isNotBlank() })

    put("app", JSONObject().apply {
        put("version", app.version)
        put("buildType", app.buildType)
        putIfPresent("commit", app.commit)
        putIfPresent("engineVersion", app.engineVersion)
        putIfPresent("locale", app.locale)
    })

    put("setup", JSONObject().apply {
        putIfPresent("assistant", setup.assistant)
        putIfPresent("model", setup.model)
        putIfPresent("stt", setup.stt)
        putIfPresent("tts", setup.tts)
        putIfPresent("wake", setup.wake)
    })

    put("device", JSONObject().apply {
        put("model", device.model)
        put("androidVersion", device.androidVersion)
        putIfPresent("fingerprint", device.fingerprint)
        putIfPresent("ramFreeMb", device.ramFreeMb)
        putIfPresent("storageFreeMb", device.storageFreeMb)
        putIfPresent("network", device.network)
        putIfPresent("batteryExempt", device.batteryExempt)
        put("permissions", JSONArray(device.permissions))
    })

    put("skills", JSONArray().apply {
        skills.forEach { skill ->
            put(JSONObject().apply {
                put("id", skill.id)
                putIfPresent("version", skill.version)
            })
        }
    })

    put("attachments", JSONArray().apply {
        attachments.forEach { attachment ->
            put(JSONObject().apply {
                put("kind", attachment.kind.wireName)
                put("bytes", attachment.bytes)
            })
        }
    })
}

/** One pre-signed upload the server handed back, matched to its local file. */
data class PendingUpload(
    val kind: AttachmentKind,
    val url: String,
    val contentType: String,
    val file: File,
)

/**
 * What the server said when the report was created: an id, the token that can
 * later withdraw it, and somewhere to put each file.
 */
data class CreatedReport(
    val reportId: String,
    val deleteToken: String,
    val uploads: List<PendingUpload>,
)

/** A filed report, as the confirmation screen shows it. */
data class FiledReport(
    val reportId: String,
    val deleteToken: String,
    val issueNumber: Int,
    val issueUrl: String,
)

/**
 * Distinct types rather than a nullable success, because the three outcomes
 * need three different things from the user: nothing, a correction, or a
 * retry.
 */
sealed interface SendOutcome {
    data class Filed(val report: FiledReport) : SendOutcome

    /** The server refused the report itself. Sending it again changes nothing. */
    data class Rejected(val reason: String) : SendOutcome

    /** Network, TLS, a 5xx. Worth another go. */
    data class Failed(val cause: Throwable) : SendOutcome
}
