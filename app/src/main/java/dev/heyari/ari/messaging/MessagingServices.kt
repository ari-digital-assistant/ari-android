package dev.heyari.ari.messaging

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One messaging service Ari knows how to target.
 *
 * [canSend] marks the ones Ari can send itself with no user interaction —
 * only SMS on Android, since every other messenger keeps sending to itself.
 *
 * [contactMimetype] and the id fields are only present for services that sync
 * the address book. Without them a service can still be targeted — the user
 * picks the recipient in the app's own picker — it just can't have one
 * resolved ahead of time.
 */
data class MessagingService(
    val id: String,
    val displayName: String,
    val packages: List<String>,
    val contactMimetype: String? = null,
    val idColumn: IdColumn = IdColumn.DATA1,
    val idStripPrefix: String? = null,
    val idStripSuffix: String? = null,
    val contactSource: ContactSource? = null,
    val urlTemplate: String? = null,
    val urlFallback: String? = null,
    val canSend: Boolean = false,
    val intentAction: IntentAction = IntentAction.VIEW,
) {
    /**
     * URIs that open this person's chat with the message already typed, most
     * preferred first. Empty when the service has no template or we never
     * resolved who to send to — the caller then falls back to a share intent.
     *
     * [encodedText] must already be percent-encoded; the caller does that with
     * the platform encoder, mirroring how [NavigationLauncher] handles it.
     */
    fun chatUris(recipientId: String?, encodedText: String): List<String> {
        val id = recipientId?.trim().orEmpty()
        if (id.isEmpty()) return emptyList()
        return listOfNotNull(urlTemplate, urlFallback)
            .map { it.replace("{id}", id).replace("{text}", encodedText) }
    }

    /**
     * The usable identifier inside a raw contacts row value.
     *
     * Affixes are compared trimmed, so a row holding nothing but its affix
     * ("Message ") yields null rather than the literal word — which would
     * otherwise become a recipient id that addresses nobody.
     */
    fun extractId(raw: String): String? {
        var v = raw.trim()
        idStripPrefix?.trim()?.let { if (v.startsWith(it)) v = v.removePrefix(it).trim() }
        idStripSuffix?.trim()?.let { if (v.endsWith(it)) v = v.removeSuffix(it).trim() }
        return v.takeIf { it.isNotBlank() }
    }

    enum class IdColumn { DATA1, DATA3 }

    /** Which standard contact field holds this service's address, for the
     *  ones that use an existing field rather than writing their own row. */
    enum class ContactSource { PHONE, EMAIL }

    /** `SENDTO` for scheme-addressed services like `mailto:`; `VIEW` for the
     *  app-specific schemes. */
    enum class IntentAction { VIEW, SENDTO }
}

/**
 * The messaging services catalogue, loaded from `assets/messaging-services.json`.
 *
 * Adding a service is a data change. That matters more than it looks: any
 * hand-written list of messengers is wrong somewhere in the world, and the
 * cost of being wrong shouldn't be an app release by whoever happens to write
 * Kotlin. A service absent from the catalogue still reaches the user through
 * the system share chooser.
 *
 * Single source of truth for both halves of the feature — the launcher that
 * targets an app, and the contacts lookup that resolves who to send to. When
 * those two lists were separate they could disagree, and a contact resolved
 * on a service the launcher had never heard of.
 */
@Singleton
class MessagingServices @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val byId: Map<String, MessagingService> by lazy { load() }

    /** Every known service, keyed by canonical id. */
    fun all(): Map<String, MessagingService> = byId

    operator fun get(id: String): MessagingService? = byId[id.lowercase()]

    /** Services that write a contacts row, keyed by their mimetype. */
    fun byMimetype(): Map<String, MessagingService> =
        byId.values.mapNotNull { s -> s.contactMimetype?.let { it to s } }.toMap()

    private fun load(): Map<String, MessagingService> =
        runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.mapCatching { parse(it) }
            .onFailure { Log.e(TAG, "could not load $ASSET", it) }
            // An unreadable catalogue costs targeting, not the feature: every
            // message still reaches the share chooser.
            .getOrDefault(emptyMap())

    companion object {
        private const val TAG = "MessagingServices"
        private const val ASSET = "messaging-services.json"

        /**
         * Parse the catalogue. Entries missing an id or a display name are
         * skipped rather than failing the whole file — one bad contributed
         * entry shouldn't take every other service down with it.
         *
         * Packages are optional: a scheme-addressed service (`mailto:`) has
         * none, because it goes to whichever client the user set as default.
         */
        fun parse(json: String): Map<String, MessagingService> {
            val services = JSONObject(json).optJSONArray("services") ?: return emptyMap()
            val out = LinkedHashMap<String, MessagingService>()
            for (i in 0 until services.length()) {
                val o = services.optJSONObject(i) ?: continue
                val id = o.optString("id").lowercase().takeIf { it.isNotBlank() } ?: continue
                val name = o.optString("display_name").takeIf { it.isNotBlank() } ?: continue
                val packages = o.optJSONArray("packages")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty()
                out[id] = MessagingService(
                    id = id,
                    displayName = name,
                    packages = packages,
                    contactMimetype = o.optString("contact_mimetype").takeIf { it.isNotBlank() },
                    idColumn = if (o.optString("id_column") == "data3") {
                        MessagingService.IdColumn.DATA3
                    } else {
                        MessagingService.IdColumn.DATA1
                    },
                    idStripPrefix = o.optString("id_strip_prefix").takeIf { it.isNotBlank() },
                    idStripSuffix = o.optString("id_strip_suffix").takeIf { it.isNotBlank() },
                    contactSource = when (o.optString("contact_source")) {
                        "phone" -> MessagingService.ContactSource.PHONE
                        "email" -> MessagingService.ContactSource.EMAIL
                        else -> null
                    },
                    urlTemplate = o.optString("url_template").takeIf { it.isNotBlank() },
                    urlFallback = o.optString("url_fallback").takeIf { it.isNotBlank() },
                    canSend = o.optBoolean("can_send", false),
                    intentAction = if (o.optString("intent_action") == "sendto") {
                        MessagingService.IntentAction.SENDTO
                    } else {
                        MessagingService.IntentAction.VIEW
                    },
                )
            }
            return out
        }
    }
}
