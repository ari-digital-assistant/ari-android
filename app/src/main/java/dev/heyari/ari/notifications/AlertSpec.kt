package dev.heyari.ari.notifications

import org.json.JSONArray
import org.json.JSONObject

/**
 * Skill-declared alert spec — exactly the alert primitive from
 * ari-skills/docs/action-responses.md.
 *
 * Travels between processes / receivers as a JSON string in intent extras
 * via [AlertSpecCodec] (rather than Parcelable, which would require pulling
 * in the kotlin-parcelize Gradle plugin for one type). [CardStateRepository]
 * uses the same codec for persistence so there's only one schema to keep
 * in sync.
 *
 * `skillId` is the emitting skill's manifest id, used by
 * [dev.heyari.ari.assets.AssetResolver] to resolve `asset:<path>` in
 * [sound] back to the skill's bundle directory.
 */
data class AlertSpec(
    val id: String,
    val skillId: String,
    val title: String,
    val body: String?,
    val urgency: Urgency,
    val sound: String,
    val speechLoop: String?,
    val autoStopMs: Long,
    val maxCycles: Int,
    val fullTakeover: Boolean,
    val actions: List<AlertAction>,
    val icon: String?,
) {
    enum class Urgency { NORMAL, HIGH, CRITICAL }

    /** `system.alarm`, `system.notification`, `system.silent`. */
    object SoundToken {
        const val ALARM = "system.alarm"
        const val NOTIFICATION = "system.notification"
        const val SILENT = "system.silent"
        const val ASSET_PREFIX = "asset:"
    }
}

data class AlertAction(
    val id: String,
    val label: String,
    val utterance: String?,
    val style: Style,
) {
    enum class Style { DEFAULT, PRIMARY, DESTRUCTIVE }
}

/**
 * JSON codec for [AlertSpec]. Used for both intent-extras transport
 * (CardExpiryReceiver → AlertService) and DataStore persistence
 * (CardStateRepository).
 */
object AlertSpecCodec {

    fun encode(spec: AlertSpec): String = encodeObject(spec).toString()

    fun decode(json: String): AlertSpec? = runCatching {
        decodeObject(JSONObject(json))
    }.getOrNull()

    fun encodeObject(spec: AlertSpec): JSONObject = JSONObject().apply {
        put("id", spec.id)
        put("skillId", spec.skillId)
        put("title", spec.title)
        put("body", spec.body ?: JSONObject.NULL)
        put("urgency", spec.urgency.name)
        put("sound", spec.sound)
        put("speechLoop", spec.speechLoop ?: JSONObject.NULL)
        put("autoStopMs", spec.autoStopMs)
        put("maxCycles", spec.maxCycles)
        put("fullTakeover", spec.fullTakeover)
        put("icon", spec.icon ?: JSONObject.NULL)
        put(
            "actions",
            JSONArray().apply {
                for (a in spec.actions) put(encodeAction(a))
            },
        )
    }

    fun decodeObject(o: JSONObject): AlertSpec? {
        val id = o.optString("id").ifEmpty { return null }
        return AlertSpec(
            id = id,
            skillId = o.optString("skillId"),
            title = o.optString("title"),
            body = if (o.isNull("body")) null else o.optString("body").takeIf { it.isNotEmpty() },
            urgency = runCatching { AlertSpec.Urgency.valueOf(o.optString("urgency", "NORMAL")) }
                .getOrDefault(AlertSpec.Urgency.NORMAL),
            sound = o.optString("sound", AlertSpec.SoundToken.NOTIFICATION),
            speechLoop = if (o.isNull("speechLoop")) null
            else o.optString("speechLoop").takeIf { it.isNotEmpty() },
            autoStopMs = o.optLong("autoStopMs", 120_000L),
            maxCycles = o.optInt("maxCycles", 12),
            fullTakeover = o.optBoolean("fullTakeover", false),
            icon = if (o.isNull("icon")) null else o.optString("icon").takeIf { it.isNotEmpty() },
            actions = o.optJSONArray("actions")?.let(::decodeActions).orEmpty(),
        )
    }

    private fun encodeAction(a: AlertAction): JSONObject = JSONObject().apply {
        put("id", a.id)
        put("label", a.label)
        put("utterance", a.utterance ?: JSONObject.NULL)
        put("style", a.style.name)
    }

    private fun decodeActions(arr: JSONArray): List<AlertAction> {
        val out = ArrayList<AlertAction>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += AlertAction(
                id = o.optString("id"),
                label = o.optString("label"),
                utterance = if (o.isNull("utterance")) null
                else o.optString("utterance").takeIf { it.isNotEmpty() },
                style = runCatching { AlertAction.Style.valueOf(o.optString("style", "DEFAULT")) }
                    .getOrDefault(AlertAction.Style.DEFAULT),
            )
        }
        return out
    }
}
